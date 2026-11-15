(ns alma-spei.ui.decisions-test
  (:require [alma-spei.ui.imports.decision-events :as events]
            [alma-spei.ui.imports.decision-effects :as effects]
            [alma-spei.ui.imports-test :as fixture]
            [alma-spei.ui.review-test :as review]
            [cljs.test :refer-macros [deftest is async]]))

(defn ready []
  (let [db (review/loaded-work) id (get-in db [:review :editor :request-id])]
    (:db (events/loaded db [:decision/loaded [:editor] id :decision-load
                            {:data {:catalog-version 5 :batch fixture/batch :row fixture/row :decision nil}}]))))
(defn selected [] (events/choose-target (ready) [:decision/target "existing-donor" fixture/other-id 3]))
(deftest selection-is-explicit-and-does-not-supply-currency-or-save
  (let [db (selected) input (events/build-input (:review db))]
    (is (= fixture/other-id (:donor-id input)))
    (is (= 3 (:target-version input)))
    (is (nil? (get-in input [:financial :currency])))
    (is (= "unresolved" (get-in input [:financial :disposition])))
    (is (events/dirty? (:review db)))
    (is (events/saveable? (:review db)))
    (is (= (:version fixture/batch) (:batch-version input)))
    (is (nil? (:decision/request (events/save db [:decision/save nil false]))))))
(deftest conflict-and-unknown-outcome-preserve-draft-and-require-explicit-reload
  (doseq [error [:conflict :uncertain]]
    (let [db (selected) saving (events/save db [:decision/save "synthetic-csrf" false])
          result (events/loaded (:db saving) [:decision/loaded [:editor] (get-in saving [:decision/request :request-id]) :decision-save {:error error}])]
      (is (= (get-in db [:review :editor :draft]) (get-in result [:db :review :editor :draft])))
      (is (get-in result [:db :review :editor :requires-reload]))
      (is (not (events/saveable? (:review (:db result)))))
      (is (nil? (:dispatch result)))
      (is (= {:db (:db result)} (events/save (:db result) [:decision/save "synthetic-csrf" false]))))))
(deftest stale-responses-and-session-expiry-cannot-repopulate-private-data
  (let [db (selected) saving (events/save db [:decision/save "synthetic" false])
        id (get-in saving [:decision/request :request-id])]
    (is (= {:db db} (events/loaded db [:decision/loaded [:editor] id :decision-save {:error :uncertain}])))
    (let [expired (events/loaded (:db saving) [:decision/loaded [:editor] id :decision-save {:error :unauthenticated}])]
      (is (= {:mode :session-error :error :unauthenticated} (get-in expired [:db :review])))
      (is (contains? expired :decision/abort))
      (is (contains? expired :review/abort)))))
(deftest auxiliary-reads-do-not-silently-refresh-accepted-batch-version
  (let [db (selected) started (events/load-aux db [:decision/aux :proposals 25])
        id (get-in started [:decision/request :request-id])
        result (events/loaded (:db started) [:decision/loaded [:aux :proposals] id :proposals
                                             {:data {:catalog-version 999 :batch (assoc fixture/batch :version 999) :proposals [] :limit 25 :offset 25 :total 25}}])]
    (is (= (:version fixture/batch) (get-in result [:db :review :editor :batch-version])))
    (is (= 5 (get-in result [:db :review :editor :catalog-version])))
    (is (= 25 (get-in started [:decision/request :offset])))))
(deftest proposal-form-starts-empty-and-success-does-not-select-it
  (let [db (events/proposal-form (ready) [:decision/proposal-form nil])
        db (reduce (fn [db [field value]] (events/proposal-field db [:decision/proposal-field field value])) db
                   [[:entity-type "individual"] [:name "Synthetic Person"] [:city "Synthetic City"]])
        checked (-> db (events/proposal-check [:decision/proposal-check :checked-existing true])
                    (events/proposal-check [:decision/proposal-check :checked-proposals true]))
        saving (events/save-proposal checked [:decision/proposal-save "synthetic"])
        result (events/loaded (:db saving) [:decision/loaded [:editor] (get-in saving [:decision/request :request-id]) :proposal-create
                                            {:data {:catalog-version 5 :batch (assoc fixture/batch :version 2) :proposal {:id fixture/other-id :version 1}}}])]
    (is (not (events/proposal-saveable? (:review db))))
    (is (events/proposal-saveable? (:review checked)))
    (is (events/dirty? (:review checked)))
    (is (= "unresolved" (get-in result [:db :review :editor :draft :kind])))
    (is (= fixture/other-id (get-in result [:db :review :editor :saved-proposal :id])))))
(deftest duplicate-selection-never-supplies-currency-or-overlap-confirmation
  (let [db (assoc-in (selected) [:review :aux :payments :data :payments] [{:id fixture/id :version 4}])
        result (events/choose-payment db [:decision/payment fixture/id])]
    (is (= 4 (get-in result [:review :editor :draft :donation-version])))
    (is (= "" (get-in result [:review :editor :draft :financial :currency])))
    (is (false? (get-in result [:review :editor :draft :financial :overlap-checked])))
    (is (not (events/saveable? (:review result))))))
(deftest preview-requires-clean-draft-and-blockers-are-read-only-paged
  (is (nil? (:decision/request (events/create-preview (selected) [:decision/preview "synthetic"]))))
  (let [start (events/create-preview (ready) [:decision/preview "synthetic"])
        result (events/loaded (:db start) [:decision/loaded [:preview] (get-in start [:decision/request :request-id]) :preview-create {:error :blocked}])]
    (is (= [:decision/aux :blockers 0] (:dispatch result)))
    (is (= :preview-create (get-in start [:decision/request :op])))))
(deftest mutation-transport-sends-csrf-and-never-retries-network-failure
  (async done
         (let [calls (atom []) replies (atom [])]
           (effects/request! (fn [path options] (swap! calls conj [path (js->clj options :keywordize-keys true)]) (js/Promise.reject (js/Error. "synthetic")))
                             #(swap! replies conj %) {:op :decision-save :batch-id fixture/id :worksheet 1 :row 1 :slot [:editor] :request-id 1
                                                      :csrf "synthetic" :input {:version 0}})
           (js/setTimeout (fn []
                            (is (= 1 (count @calls)))
                            (is (= "PUT" (get-in @calls [0 1 :method])))
                            (is (= "synthetic" (get-in @calls [0 1 :headers :X-CSRF-Token])))
                            (is (= [[:decision/loaded [:editor] 1 :decision-save {:error :uncertain}]] @replies))
                            (done)) 20))))

(deftest proposal-edit-response-cannot-renew-old-financial-evidence
  (let [db (ready) request (events/request db [:editor] :proposal-update {})
        result (events/loaded (:db request) [:decision/loaded [:editor] (get-in request [:decision/request :request-id]) :proposal-update
                                             {:data {:catalog-version 999 :batch fixture/batch :proposal {}}}])]
    (is (= 5 (get-in result [:db :review :editor :catalog-version])))))

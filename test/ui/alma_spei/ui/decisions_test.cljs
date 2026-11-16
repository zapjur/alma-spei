(ns alma-spei.ui.decisions-test
  (:require [alma-spei.ui.imports.decision-events :as events]
            [alma-spei.ui.imports.decision-effects :as effects]
            [alma-spei.ui.imports-test :as fixture]
            [alma-spei.ui.review-test :as review]
            [alma-spei.ui.imports.review-events :as review-events]
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

(defn complete-draft []
  (reduce (fn [db [field value]] (events/field db [:decision/field [:financial field] value]))
          (selected) [[:currency "PLN"] [:evidence "Fikcyjny wyciąg PLN; sprawdzono wcześniejsze wpłaty."]
                      [:disposition "new"] [:overlap-checked true]]))

(deftest save-and-next-waits-for-confirmed-complete-save
  (let [db (complete-draft)
        saving (events/save-next db [:decision/save-next "csrf"])
        id (get-in saving [:decision/request :request-id])
        decision (assoc (events/build-input (:review db)) :complete true)
        response {:data {:catalog-version 5 :batch fixture/batch :decision decision}}
        result (events/loaded (:db saving) [:decision/loaded [:editor] id :decision-save response])]
    (is (events/complete-saveable? (:review db)))
    (is (nil? (:dispatch saving)))
    (is (= [:review/advance "csrf"] (:dispatch result)))
    (is (not (events/dirty? (:review (:db result)))))
    (doseq [error [:validation :conflict :uncertain :unauthenticated]]
      (let [failed (events/loaded (:db saving) [:decision/loaded [:editor] id :decision-save {:error error}])]
        (is (nil? (:dispatch failed)))
        (is (nil? (get-in failed [:db :review :editor :advance-after-save])))))
    (is (= {:db db} (events/loaded db [:decision/loaded [:editor] id :decision-save response])))
    (is (nil? (:dispatch (events/loaded (:db saving) [:decision/loaded [:editor] id :decision-save
                                                      (assoc-in response [:data :decision :complete] false)]))))))

(deftest incomplete-invalid-and-negative-rows-cannot-advance-by-saving
  (doseq [db [(selected)
              (assoc-in (complete-draft) [:review :editor :row :validation-state] "invalid")
              (assoc-in (complete-draft) [:review :editor :row :payload :amount] "-25.00")]]
    (is (nil? (:decision/request (events/save-next db [:decision/save-next "csrf"]))))))

(deftest advance-selects-next-row-pages-and-opens-summary-without-skipping-a-draft
  (let [db (ready) second-row (assoc fixture/row :row-number 8)
        two-rows (assoc-in db [:review :work :rows] [fixture/row second-row])
        next-row (review-events/advance two-rows [:review/advance "csrf"])]
    (is (= {:worksheet 1 :row 8} (get-in next-row [:db :review :selected])))
    (is (= :decision-load (get-in next-row [:decision/request :op])))
    (let [last-row (-> db (assoc-in [:review :work :total] 1) (assoc-in [:review :work :rows] [fixture/row]))
          result (review-events/advance last-row [:review/advance "csrf"])]
      (is (= :summary (get-in result [:db :review :stage])))
      (is (= [:decision/preview "csrf"] (:dispatch result))))
    (let [last-on-page (assoc-in db [:review :selected] {:worksheet 1 :row 25})
          result (review-events/advance last-on-page [:review/advance "csrf"])]
      (is (= 25 (get-in result [:review/request :offset])))
      (is (nil? (get-in result [:db :review :editor]))))
    (is (= {:db (selected)} (review-events/advance (selected) [:review/advance "csrf"])))))

(deftest choosing-new-profile-on-save-is-explicit
  (let [db (-> (ready)
               (assoc-in [:review :editor :use-after-save] true)
               (assoc-in [:review :editor :request-id] 99))
        result (events/loaded db [:decision/loaded [:editor] 99 :proposal-create
                                  {:data {:batch fixture/batch :proposal {:id fixture/other-id :version 2}}}])]
    (is (= "new-proposal" (get-in result [:db :review :editor :draft :kind])))
    (is (= 2 (get-in result [:db :review :editor :draft :target-version])))
    (is (= "" (get-in result [:db :review :editor :draft :financial :currency])))
    (is (nil? (:dispatch result)))))

(deftest blocker-navigation-finds-physical-row-across-pages-and-ignores-stale-results
  (let [started (review-events/find-row (ready) [:review/find-row 1 73])
        first-id (get-in started [:review/request :request-id])
        first-page (assoc review/page :rows [fixture/row] :offset 0 :limit 25 :total 26)
        next-page (review-events/loaded (:db started) [:review/loaded :rows first-id {:data first-page}])
        next-id (get-in next-page [:review/request :request-id])
        found (review-events/loaded (:db next-page)
                                    [:review/loaded :rows next-id {:data (assoc first-page :offset 25 :rows [(assoc fixture/row :row-number 73)])}])]
    (is (= 25 (get-in next-page [:review/request :offset])))
    (is (= {:worksheet 1 :row 73} (get-in found [:db :review :selected])))
    (is (= :decision-load (get-in found [:decision/request :op])))
    (is (nil? (get-in found [:db :review :find-row])))
    (is (= {:db (:db next-page)}
           (review-events/loaded (:db next-page) [:review/loaded :rows first-id {:data first-page}])))))

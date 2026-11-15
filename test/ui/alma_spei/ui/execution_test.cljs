(ns alma-spei.ui.execution-test
  (:require [alma-spei.ui.imports.execution-events :as events]
            [alma-spei.ui.imports.execution-effects :as effects]
            [alma-spei.ui.imports.execution-location :as location]
            [alma-spei.ui.imports.events :as uploads]
            [alma-spei.ui.imports-test :as fixture]
            [alma-spei.ui.auth.views :as auth]
            [cljs.test :refer-macros [deftest is async]]))

(def batch fixture/batch)
(def now (js/Date.parse "2026-11-15T12:00:00Z"))
(def preview {:id fixture/other-id :batch-id fixture/id :batch-version (:version batch) :catalog-version 5
              :created-by fixture/other-id :created-at "2026-11-15T11:00:00Z" :rule-version "review-preview-v2"
              :effect-digest (apply str (repeat 64 "a")) :item-count 0
              :summary {:new-donors 0 :new-payments 0 :duplicate-references 0 :totals []}})
(def preview-response {:batch batch :catalog-version 5 :preview preview :stale false})
(def outcome {:batch batch :uploaded-by-name "Synthetic uploader" :commit nil :undo nil})
(def receipt {:id fixture/other-id :adapter "synthetic-test-v1" :synthetic true :completed-at "2026-11-15T11:59:59Z" :expires-at "2026-11-15T12:05:00Z"})
(def route {:mode :confirm :batch-id fixture/id :preview-id fixture/other-id :offset 0})
(defn opened [] (events/open {} [:execution/open route now]))
(defn ready []
  (assoc (:db (opened)) :execution {:route route :now now :consent false
                                    :capabilities {:status :ready :data {:mode "synthetic-test-v1" :commit-enabled true}}
                                    :preview {:status :ready :data preview-response} :outcome {:status :ready :data outcome}}))
(defn backed []
  (let [db (ready) start (events/start-backup db [:execution/backup "synthetic-csrf"])]
    (:db (events/loaded (:db start) [:execution/loaded :mutation (get-in start [:execution/request :request-id]) :backup {:data {:batch batch :backup receipt}} "synthetic-csrf"]))))
(defn approved [] (events/consent (backed) [:execution/consent true]))
(defn commit-request []
  (let [start (events/start-commit (approved) [:execution/commit "synthetic-csrf"])]
    (events/loaded (:db start) [:execution/loaded :preflight (get-in start [:execution/request :request-id]) :preview {:data preview-response} "synthetic-csrf"])))

(deftest uuid-routes-are-strict-and-resume-through-authentication
  (doseq [route [{:mode :history :offset 25} {:mode :batch :batch-id fixture/id :offset 0} route {:mode :reimport :batch-id fixture/id :offset 0}]]
    (is (= route (location/parse (location/fragment route)))))
  (doseq [fragment ["#imports/history/-1" "#imports/history/100000" "#imports/history/0/" "#imports/batch/private/0"
                    (str "#imports/confirm/" fixture/id "/" fixture/other-id "/0?confirmed=true") "#imports/unknown/0"]]
    (is (nil? (location/parse fragment))))
  (is (= :execution (:screen (auth/result-state {:execution-route route} :login {:user {:id fixture/id :password-change-required false} :csrf "synthetic"}))))
  (is (nil? (:execution-route (auth/result-state {:execution-route route} :logout {:user nil})))))

(deftest mounting-only-reads-and-consent-never-requests-backup-or-commit
  (is (= #{:capabilities :outcome :preview :preview-items} (set (map #(get-in % [1 :op]) (:fx (opened))))))
  (is (events/can-backup? (:execution (ready))))
  (is (not (events/can-commit? (:execution (ready)))))
  (is (= (ready) (events/consent (ready) [:execution/consent true])))
  (is (false? (get-in (backed) [:execution :consent])))
  (is (not (.includes (pr-str (backed)) "synthetic-csrf"))))

(deftest consent-and-receipt-bind-the-exact-preview-and-expire
  (is (events/can-commit? (:execution (approved))))
  (doseq [changed [(assoc-in (approved) [:execution :preview :data :stale] true)
                   (assoc-in (approved) [:execution :capabilities :data :commit-enabled] false)
                   (assoc-in (approved) [:execution :preview :data :preview :effect-digest] (apply str (repeat 64 "b")))
                   (assoc-in (approved) [:execution :outcome :data :batch :version] 99)]]
    (is (not (events/can-commit? (:execution changed)))))
  (let [expired (events/tick (approved) [:execution/tick (+ now 300000)])]
    (is (false? (get-in expired [:execution :consent])))
    (is (not (events/can-commit? (:execution expired))))))

(deftest commit-first-rechecks-read-metadata-and-guards-double-click
  (let [db (approved) start (events/start-commit db [:execution/commit "synthetic-csrf"]) writing (commit-request)]
    (is (= :preview (get-in start [:execution/request :op])))
    (is (= {:db (:db start)} (events/start-commit (:db start) [:execution/commit "synthetic-csrf"])))
    (is (= :commit (get-in writing [:execution/request :op])))
    (is (= {:batch-id fixture/id :batch-version (:version batch) :preview-id fixture/other-id :effect-digest (:effect-digest preview)
            :catalog-version 5 :backup-id fixture/other-id :confirmed true} (get-in writing [:execution/request :input])))
    (is (not (.includes (pr-str (:db writing)) "synthetic-csrf")))
    (let [stale (events/loaded (:db start) [:execution/loaded :preflight (get-in start [:execution/request :request-id]) :preview {:data (assoc preview-response :stale true)} "synthetic-csrf"])]
      (is (nil? (:execution/request stale)))
      (is (false? (get-in stale [:db :execution :consent]))))))

(deftest uncertain-write-never-retries-until-explicit-read-and-exact-retry
  (let [writing (commit-request) id (get-in writing [:execution/request :request-id])
        unknown (events/loaded (:db writing) [:execution/loaded :mutation id :commit {:error :uncertain} "synthetic-csrf"])
        original (get-in writing [:execution/request :input])
        check (events/check-outcome (:db unknown) [])
        checked (events/loaded (:db check) [:execution/loaded :outcome (get-in check [:execution/request :request-id]) :outcome {:data outcome} nil])
        retry (events/retry-exact (:db checked) [:execution/retry-exact "synthetic-csrf"])]
    (is (nil? (:fx unknown)))
    (is (nil? (:execution/request unknown)))
    (is (= {:db (:db unknown)} (events/retry-exact (:db unknown) [:execution/retry-exact "synthetic-csrf"])))
    (is (= :outcome (get-in check [:execution/request :op])))
    (is (= original (get-in retry [:execution/request :input])))
    (is (= :commit (get-in retry [:execution/request :op])))))

(deftest late-response-and-session-loss-cannot-repopulate-another-route
  (let [writing (commit-request) closed (:db (events/close (:db writing) []))
        reopened (:db (events/open closed [:execution/open {:mode :history :offset 0} now]))
        id (get-in writing [:execution/request :request-id])]
    (is (= {:db reopened} (events/loaded reopened [:execution/loaded :mutation id :commit {:data outcome} nil])))
    (let [purged (events/loaded (:db writing) [:execution/loaded :mutation id :commit {:error :unauthenticated} nil])]
      (is (= {:session-error :unauthenticated} (get-in purged [:db :execution])))
      (is (contains? purged :execution/abort))
      (is (contains? purged :imports/abort)))))

(deftest undo-requires-eligible-modal-and-cancellation-is-read-only
  (let [data (assoc outcome :batch (assoc batch :state "committed") :commit {:id fixture/other-id})
        db (-> (ready) (assoc-in [:execution :outcome :data] data)
               (assoc-in [:execution :eligibility :data] {:eligible true :commit-id fixture/other-id :batch (:batch data)}))
        modal (events/open-undo db []) writing (events/start-undo modal [:execution/undo "synthetic"])]
    (is (= {:db db} (events/start-undo db [:execution/undo "synthetic"])))
    (is (= db (events/cancel-undo modal [])))
    (is (= :undo (get-in writing [:execution/request :op])))
    (is (= {:batch-id fixture/id :batch-version (:version batch) :commit-id fixture/other-id :confirmed true} (get-in writing [:execution/request :input])))
    (is (= (:db writing) (events/cancel-undo (:db writing) [])))))

(deftest reimport-requires-new-file-separator-and-explicit-confirmation
  (let [db (uploads/open {} [:imports/open (assoc batch :state "undone")])
        selected (-> db (uploads/choose-file [:imports/choose-file {:name "synthetic.csv" :size 100}]) (uploads/separator [:imports/separator ";"]))
        writing (uploads/start selected [:imports/start :synthetic-file "synthetic-csrf" true true])]
    (is (= :retry-ready (get-in db [:imports :status])))
    (is (nil? (get-in db [:imports :file])))
    (is (= {:db selected} (uploads/start selected [:imports/start :synthetic-file "synthetic-csrf" true false])))
    (is (= {:db selected} (uploads/start selected [:imports/start :synthetic-file "synthetic-csrf" false false])))
    (is (= {:id fixture/id :version (:version batch)} (get-in writing [:imports/upload :retry])))
    (is (= :synthetic-file (get-in writing [:imports/upload :file])))))

(deftest response-validation-checks-identities-pages-and-capability-consistency
  (is (effects/valid? {:commit-enabled false :mode "disabled"} {:op :capabilities}))
  (is (not (effects/valid? {:commit-enabled true :mode "disabled"} {:op :capabilities})))
  (is (effects/valid? preview-response {:op :preview :batch-id fixture/id :preview-id fixture/other-id}))
  (is (not (effects/valid? preview-response {:op :preview :batch-id fixture/id :preview-id fixture/id})))
  (let [history {:batches [batch] :uploaders [{:id (:created-by batch) :display-name "Synthetic uploader"}] :total 1 :limit 25 :offset 0}]
    (is (effects/valid? history {:op :history :offset 0}))
    (is (not (effects/valid? (assoc history :uploaders []) {:op :history :offset 0})))
    (is (not (effects/valid? history {:op :history :offset 25})))))

(deftest mutation-transport-never-retries-a-network-error
  (async done
         (let [calls (atom []) replies (atom [])]
           (effects/request! (fn [path options] (swap! calls conj [path (js->clj options :keywordize-keys true)]) (js/Promise.reject (js/Error. "synthetic")))
                             #(swap! replies conj %) {:op :commit :slot :mutation :request-id 1 :batch-id fixture/id :csrf "synthetic" :input {}})
           (js/setTimeout (fn []
                            (is (= 1 (count @calls)))
                            (is (= "POST" (get-in @calls [0 1 :method])))
                            (is (= "synthetic" (get-in @calls [0 1 :headers :X-CSRF-Token])))
                            (is (= {:error :uncertain} (get-in @replies [0 4])))
                            (done)) 20))))

(deftest nested-reimport-auth-failure-clears-all-private-import-state
  (let [start (uploads/start (-> (uploads/open {} [:imports/open (assoc batch :state "undone")])
                                 (uploads/choose-file [:imports/choose-file {:name "synthetic.csv" :size 100}])
                                 (uploads/separator [:imports/separator ";"])) [:imports/start :file "synthetic" true true])
        db (assoc (:db start) :execution {:outcome {:data outcome}} :review {:private-source "synthetic"})
        result (uploads/finished db [:imports/finished (get-in start [:imports/upload :request-id]) {:error :unauthenticated}])]
    (is (= {:session-error :unauthenticated} (get-in result [:db :execution])))
    (is (nil? (get-in result [:db :review])))
    (is (nil? (get-in result [:db :imports :file])))
    (is (contains? result :execution/abort))))

(deftest uncertain-successor-report-uses-the-new-known-attempt-id
  (let [db (uploads/open {} [:imports/open (assoc batch :state "undone")])
        db (assoc-in db [:imports :batch-id] fixture/other-id)
        loading (uploads/reload db [])]
    (is (= fixture/other-id (get-in loading [:imports/page :batch-id])))))

(deftest backup-conflict-refreshes-staleness-without-another-mutation
  (let [start (events/start-backup (ready) [:execution/backup "synthetic"])
        result (events/loaded (:db start) [:execution/loaded :mutation (get-in start [:execution/request :request-id]) :backup {:error :conflict} "synthetic"])]
    (is (= #{:outcome :eligibility :preview} (set (map #(get-in % [1 :op]) (:fx result)))))
    (is (false? (get-in result [:db :execution :consent])))
    (is (nil? (get-in result [:db :execution :backup])))))

(deftest a-new-batch-version-clears-approval-and-receipt
  (let [start (events/check-outcome (approved) [])
        result (events/loaded (:db start) [:execution/loaded :outcome (get-in start [:execution/request :request-id]) :outcome
                                           {:data (assoc-in outcome [:batch :version] 99)} nil])]
    (is (false? (get-in result [:db :execution :consent])))
    (is (nil? (get-in result [:db :execution :backup])))))

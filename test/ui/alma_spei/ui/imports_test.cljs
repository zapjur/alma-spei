(ns alma-spei.ui.imports-test
  (:require [alma-spei.ui.imports.effects :as effects]
            [alma-spei.ui.imports.events :as events]
            [alma-spei.ui.imports.views :as views]
            [re-frame.core :as rf]
            [re-frame.db :as rf-db]
            [cljs.test :refer-macros [deftest is]]))

(def id "00000000-0000-0000-0000-000000000001")
(def other-id "00000000-0000-0000-0000-000000000002")
(def batch {:id id :file-id other-id :attempt-number 1 :retry-of nil :source-type "bank" :original-filename "synthetic.csv"
            :byte-count 100 :created-by other-id :created-at "2026-11-08T10:00:00Z" :updated-at "2026-11-08T10:00:00Z"
            :version 2 :state "review" :failure-code nil :parse-version "bank-parser-v1" :mapping-version "bank-four-columns-v1"
            :total-rows 26 :valid-rows 25 :invalid-rows 1 :reviewed-rows 0})
(def row {:worksheet-index 1 :row-number 1 :validation-state "valid" :errors []
          :payload {:date "2026-01-02" :sender "Synthetic Żółć" :description "Synthetic description"
                    :amount "9999999999999999.9999" :currency nil :source-date "02.01.2026" :source-amount "9999999999999999,9999"}})
(def data {:batch batch :rows (mapv #(assoc row :row-number (inc %)) (range 25)) :total 26 :limit 25 :offset 0 :skipped-empty-rows 1})
(def failed-batch (assoc batch :state "failed" :failure-code "parse-failed" :total-rows nil :valid-rows nil :invalid-rows nil :reviewed-rows nil))
(def descriptor {:name "synthetic.csv" :size 100})
(defn selected [] (-> (events/open {} [])
                      (events/choose-file [:imports/choose-file descriptor])
                      (events/separator [:imports/separator ";"])))
(defn started [] (events/start (selected) [:imports/start :synthetic-file "synthetic-csrf" false false]))
(defn ready [] (:db (events/finished (:db (started)) [:imports/finished 2 {:data data}])))

(deftest client-file-preflight-and-explicit-csv-separator
  (is (nil? (events/file-error descriptor)))
  (is (nil? (events/file-error {:name "SYNTHETIC.XLSX" :size 10})))
  (doseq [[file error] [[{:name "synthetic.xls" :size 10} :file-type]
                        [{:name "../synthetic.csv" :size 10} :filename]
                        [{:name "synthetic.csv" :size 0} :file-size]
                        [{:name "synthetic.csv" :size (inc events/maximum-file-size)} :file-size]]]
    (is (= error (events/file-error file))))
  (let [db (events/choose-file (events/open {} []) [:imports/choose-file descriptor])]
    (is (not (events/selectable? (:imports db))))
    (is (= {:db db} (events/start db [:imports/start :file "csrf" false false])))
    (is (events/selectable? (:imports (events/separator db [:imports/separator ","])))))
  (is (events/selectable? {:file {:name "synthetic.xlsx" :size 10}})))

(deftest upload-progress-and-double-submit-guards
  (let [start (started) db (:db start)
        progressed (events/progress db [:imports/progress 2 52 false])
        sent (events/progress progressed [:imports/progress 2 100 true])]
    (is (= :uploading (get-in db [:imports :status])))
    (is (= :synthetic-file (get-in start [:imports/upload :file])))
    (is (= "synthetic-csrf" (get-in start [:imports/upload :csrf])))
    (is (not (contains? (:imports db) :csrf)))
    (is (= descriptor (get-in db [:imports :file])))
    (is (= 52 (get-in progressed [:imports :progress])))
    (is (true? (get-in sent [:imports :sent?])))
    (is (= {:db db} (events/start db [:imports/start :file "csrf" false false])))
    (is (= db (events/choose-file db [:imports/choose-file {:name "other.xlsx" :size 5}])))
    (is (= db (events/separator db [:imports/separator ","])))
    (is (= {:db db} (events/reset-screen db [])))))

(deftest persisted-report-includes-invalid-counts-and-bounded-pages
  (let [db (ready) page (events/change-page db [:imports/page :next])]
    (is (= :ready (get-in db [:imports :status])))
    (is (= 1 (get-in db [:imports :batch :invalid-rows])))
    (is (= (:rows data) (get-in db [:imports :rows])))
    (is (nil? (get-in db [:imports :file])))
    (is (= {:batch-id id :offset 25 :limit 25 :request-id 3} (:imports/page page)))
    (is (= [] (get-in page [:db :imports :rows])))
    (is (= {:db (:db page)} (events/change-page (:db page) [:imports/page :next])))
    (let [done (:db (events/page-loaded (:db page) [:imports/page-loaded 3 {:data (-> data (dissoc :skipped-empty-rows) (assoc :offset 25 :rows [(assoc row :row-number 26)]))}]))]
      (is (= {:db done} (events/change-page done [:imports/page :next])))
      (is (= 0 (get-in (events/change-page done [:imports/page :previous]) [:imports/page :offset]))))
    (is (= "9999999999999999,9999" (views/money (get-in row [:payload :amount]))))))

(deftest failed-attempt-requires-explicit-confirmation-and-reselected-file
  (let [db (:db (events/finished (:db (started)) [:imports/finished 2 {:error :invalid-file :batch failed-batch :batch-id id}]))
        selected (events/choose-file db [:imports/choose-file descriptor])
        retry (events/start selected [:imports/start :file "csrf" true true])]
    (is (= :failed (get-in db [:imports :status])))
    (is (nil? (get-in db [:imports :file])))
    (is (= {:db db} (events/start db [:imports/start :file "csrf" true true])))
    (is (= {:db selected} (events/start selected [:imports/start :file "csrf" true false])))
    (is (= {:db selected} (events/start selected [:imports/start :file "csrf" false false])))
    (is (= {:id id :version 2} (get-in retry [:imports/upload :retry])))
    (is (= :uploading (get-in retry [:db :imports :status])))
    (is (= :failed (get-in (events/finished (:db (started)) [:imports/finished 2 {:error :uncertain :batch failed-batch}]) [:db :imports :status])))))

(deftest uncertain-outcomes-never-automatically-reupload
  (let [failed (events/finished (:db (started)) [:imports/finished 2 {:error :uncertain :batch-id id}])
        db (:db failed) reload (events/reload db [])]
    (is (= #{:db} (set (keys failed))))
    (is (= :uncertain (get-in db [:imports :status])))
    (is (= {:db db} (events/start db [:imports/start :file "csrf" false false])))
    (is (= id (get-in reload [:imports/page :batch-id])))
    (is (not= (get-in db [:imports :form-key]) (get-in (events/reset-screen db []) [:db :imports :form-key])))))

(deftest stale-progress-and-results-cannot-repopulate-another-screen
  (let [db (:db (started)) closed (:db (events/close db [])) reopened (events/open closed [])]
    (is (= {:db closed} (events/finished closed [:imports/finished 2 {:data data}])))
    (is (= {:db reopened} (events/finished reopened [:imports/finished 2 {:data data}])))
    (is (= reopened (events/progress reopened [:imports/progress 2 80 false])))
    (is (nil? (:imports closed)))
    (is (> (:imports-sequence reopened) 2))))

(deftest auth-failures-purge-source-data-and-block-retry
  (doseq [error [:unauthenticated :forbidden]]
    (let [db (:db (events/finished (:db (started)) [:imports/finished 2 {:error error}]))]
      (is (= :session-error (get-in db [:imports :status])))
      (is (= [] (get-in db [:imports :rows])))
      (is (nil? (get-in db [:imports :file])))
      (is (nil? (get-in db [:imports :batch])))
      (is (= {:db db} (events/start db [:imports/start :file "csrf" false false])))))
  (let [loading (:db (events/reload (ready) []))
        purged (:db (events/page-loaded loading [:imports/page-loaded 3 {:error :unauthenticated}]))]
    (is (nil? (get-in purged [:imports :batch])))))

(defn fake-xhr []
  (let [calls (atom []) xhr #js {:upload #js {}}]
    (set! (.-open xhr) (fn [& args] (swap! calls conj (into [:open] args))))
    (set! (.-setRequestHeader xhr) (fn [& args] (swap! calls conj (into [:header] args))))
    (set! (.-send xhr) (fn [& args] (swap! calls conj (into [:send] args))))
    (set! (.-abort xhr) (fn [] (swap! calls conj [:abort]) (when (.-onabort xhr) ((.-onabort xhr)))))
    {:xhr xhr :calls calls}))
(defn respond! [xhr status body]
  (set! (.-status xhr) status)
  (set! (.-responseText xhr) (js/JSON.stringify (clj->js body)))
  ((.-onload xhr)))

(deftest xhr-upload-sends-browser-multipart-and-real-progress
  (let [{:keys [xhr calls]} (fake-xhr) received (atom []) file (js/File. #js ["Synthetic"] "synthetic.csv")]
    (effects/request! (constantly xhr) #(swap! received conj %) {:request-id 7 :file file :csrf "synthetic-csrf" :separator ";"} true)
    (is (= [:open "POST" "/api/imports/upload" true] (first @calls)))
    (is (true? (.-withCredentials xhr)))
    (is (not-any? #(= [:header "Content-Type"] (subvec % 0 (min 2 (count %)))) @calls))
    (let [body (second (last @calls))]
      (is (= file (.get body "file")))
      (is (= "bank" (.get body "source")))
      (is (= ";" (.get body "separator"))))
    ((.. xhr -upload -onprogress) #js {:lengthComputable true :loaded 1 :total 2})
    ((.. xhr -upload -onload))
    (respond! xhr 201 data)
    ((.-onerror xhr))
    (is (= [[:imports/progress 7 50 false] [:imports/progress 7 100 true] [:imports/finished 7 {:data data}]] @received))))

(deftest xhr-explicit-retry-and-validated-failure-metadata
  (let [{:keys [xhr calls]} (fake-xhr) received (atom [])]
    (effects/request! (constantly xhr) #(swap! received conj %) {:request-id 1 :file (js/File. #js ["Synthetic"] "synthetic.csv")
                                                                 :csrf "csrf" :separator ";" :retry {:id id :version 2}} true)
    (let [body (second (last @calls))]
      (is (= id (.get body "retry-batch-id")))
      (is (= "2" (.get body "version")))
      (is (= "true" (.get body "confirmed"))))
    (respond! xhr 400 {:code "parse-failed" :message "Synthetic private message" :batch failed-batch :batch-id id})
    (is (= [[:imports/finished 1 {:error :invalid-file :batch failed-batch :batch-id id}]] @received))))

(deftest xhr-errors-never-reflect-arbitrary-server-text-or-exceptions
  (doseq [[status expected] [[401 :unauthenticated] [403 :forbidden] [409 :duplicate] [413 :too-large] [415 :file-type] [503 :busy] [500 :uncertain]]]
    (let [{:keys [xhr]} (fake-xhr) received (atom [])]
      (effects/request! (constantly xhr) #(swap! received conj %) {:request-id 1 :file (js/File. #js ["Synthetic"] "synthetic.xlsx") :csrf "csrf"} true)
      (respond! xhr status {:message "Synthetic private value" :batch {:unexpected "Synthetic private value"} :batch-id "private"})
      (is (= [[:imports/finished 1 {:error expected}]] @received))))
  (doseq [callback ["onerror" "ontimeout" "onabort"]]
    (let [{:keys [xhr]} (fake-xhr) received (atom [])]
      (effects/request! (constantly xhr) #(swap! received conj %) {:request-id 1 :file (js/File. #js ["Synthetic"] "synthetic.csv") :csrf "csrf"} true)
      ((aget xhr callback))
      (is (= [[:imports/finished 1 {:error :uncertain}]] @received)))))

(deftest pages-validate-batch-identity-offset-and-contract
  (doseq [[body expected] [[(dissoc data :skipped-empty-rows) {:data (dissoc data :skipped-empty-rows)}]
                           [(assoc (dissoc data :skipped-empty-rows) :batch (assoc batch :id other-id)) {:error :invalid-response}]
                           [(assoc (dissoc data :skipped-empty-rows) :offset 25) {:error :invalid-response}]
                           [(assoc (dissoc data :skipped-empty-rows) :rows []) {:error :invalid-response}]
                           [{:unexpected true} {:error :invalid-response}]]]
    (let [{:keys [xhr calls]} (fake-xhr) received (atom [])]
      (effects/request! (constantly xhr) #(swap! received conj %) {:request-id 3 :batch-id id :offset 0 :limit 25} false)
      (is (= [:open "GET" (str "/api/imports/" id "/rows?limit=25&offset=0") true] (first @calls)))
      (respond! xhr 200 body)
      (is (= [[:imports/page-loaded 3 expected]] @received)))))

(deftest abort-prevents-late-events-and-registered-handlers-use-app-db
  (let [{:keys [xhr calls]} (fake-xhr) received (atom [])]
    (effects/request! (constantly xhr) #(swap! received conj %) {:request-id 3 :batch-id id :offset 0 :limit 25} false)
    (effects/abort!)
    (respond! xhr 200 (dissoc data :skipped-empty-rows))
    (is (= [:abort] (last @calls)))
    (is (= [] @received)))
  (let [before @rf-db/app-db captured (atom nil)]
    (try
      (reset! rf-db/app-db {})
      (with-redefs [effects/request! (fn [_ _ input upload?] (reset! captured [input upload?]))]
        (rf/dispatch-sync [:imports/open])
        (rf/dispatch-sync [:imports/choose-file descriptor])
        (rf/dispatch-sync [:imports/separator ";"])
        (rf/dispatch-sync [:imports/start :file "csrf" false false])
        (is (= :uploading (get-in @rf-db/app-db [:imports :status])))
        (is (= 2 (get-in @captured [0 :request-id])))
        (is (not (contains? @rf-db/app-db :db)))
        (rf/dispatch-sync [:imports/close])
        (is (nil? (:imports @rf-db/app-db))))
      (finally (reset! rf-db/app-db before)))))

(deftest malformed-upload-success-keeps-a-validated-batch-id-for-read-recovery
  (let [{:keys [xhr]} (fake-xhr) received (atom [])]
    (effects/request! (constantly xhr) #(swap! received conj %) {:request-id 8 :file (js/File. #js ["Synthetic"] "synthetic.csv") :csrf "csrf"} true)
    (respond! xhr 201 (assoc data :rows []))
    (is (= [[:imports/finished 8 {:error :uncertain :batch-id id}]] @received))))

(deftest recorded-server-failure-is-distinct-from-an-unknown-outcome
  (let [{:keys [xhr]} (fake-xhr) received (atom [])]
    (effects/request! (constantly xhr) #(swap! received conj %) {:request-id 9 :file (js/File. #js ["Synthetic"] "synthetic.csv") :csrf "csrf"} true)
    (respond! xhr 500 {:code "internal-error" :batch failed-batch :batch-id id})
    (is (= [[:imports/finished 9 {:error :processing-failed :batch failed-batch :batch-id id}]] @received))))

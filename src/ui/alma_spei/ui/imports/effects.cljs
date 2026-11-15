(ns alma-spei.ui.imports.effects
  (:require [alma-spei.common.contracts.imports :as contract]
            [alma-spei.common.validation :as validation]
            [re-frame.core :as rf]))

(defonce pending (atom nil))
(defn abort! []
  (when-let [xhr @pending]
    (reset! pending nil)
    (.abort xhr)))
(defn safe-body [text]
  (try (when (and (string? text) (<= (count text) (* 4 1024 1024)))
         (js->clj (js/JSON.parse text) :keywordize-keys true))
       (catch :default _ nil)))
(defn batch-info [body]
  (let [batch (:batch body)
        valid-batch? (and (validation/valid? contract/batch batch) (= "bank" (:source-type batch)))
        id (if (validation/valid? contract/identifier (:batch-id body)) (:batch-id body)
               (when valid-batch? (:id batch)))]
    (cond-> {}
      id (assoc :batch-id id)
      (and valid-batch? (= "failed" (:state batch))) (assoc :batch batch :batch-id (:id batch)))))
(defn error-kind [status body retry? mutation?]
  (case status
    401 :unauthenticated 403 :forbidden 413 :too-large 415 :file-type 503 :busy 404 :not-found
    409 (if retry? :retry-conflict :duplicate)
    400 (case (:code body) "empty-file" :empty-file "validation" :validation :invalid-file)
    (if mutation? :uncertain :unavailable)))
(defn valid-page? [data {:keys [batch-id offset limit]} upload?]
  (and (validation/valid? (if upload? contract/upload-response contract/rows-response) data)
       (= "bank" (get-in data [:batch :source-type]))
       (or (not upload?) (= "review" (get-in data [:batch :state])))
       (or upload? (= batch-id (get-in data [:batch :id])))
       (= (:limit data) (if upload? 25 limit)) (= (:offset data) (if upload? 0 offset))
       (= (count (:rows data)) (min (:limit data) (max 0 (- (:total data) (:offset data)))))
       (= (:total data) (or (get-in data [:batch :total-rows]) 0))))
(defn result [xhr request upload?]
  (let [status (.-status xhr) body (safe-body (.-responseText xhr))]
    (cond
      (#{401 403} status) {:error (error-kind status nil false upload?)}
      (<= 200 status 299) (if (valid-page? body request upload?) {:data body}
                              (merge {:error (if upload? :uncertain :invalid-response)}
                                     (when upload? (batch-info body))))
      :else (let [info (when upload? (batch-info body))
                  error (error-kind status body (some? (:retry request)) upload?)]
              (merge {:error (if (and (= error :uncertain) (:batch info)) :processing-failed error)} info)))))

(defn request! [xhr-factory dispatch {:keys [request-id file csrf separator retry batch-id offset limit] :as request} upload?]
  (abort!)
  (let [xhr (xhr-factory)
        done? (atom false)
        reply (fn [value]
                (when (and (identical? xhr @pending) (compare-and-set! done? false true))
                  (reset! pending nil)
                  (dispatch [(if upload? :imports/finished :imports/page-loaded) request-id value])))
        progress (fn [percent sent?]
                   (when (and (identical? xhr @pending) (not @done?))
                     (dispatch [:imports/progress request-id percent sent?])))
        path (if upload? "/api/imports/upload"
                 (str "/api/imports/" (js/encodeURIComponent batch-id) "/rows?limit=" limit "&offset=" offset))]
    (reset! pending xhr)
    (try
      (.open xhr (if upload? "POST" "GET") path true)
      (set! (.-withCredentials xhr) true)
      (set! (.-timeout xhr) 120000)
      (.setRequestHeader xhr "Accept" "application/json")
      (when upload?
        (.setRequestHeader xhr "X-CSRF-Token" csrf)
        (set! (.. xhr -upload -onprogress)
              (fn [event]
                (progress (when (and (.-lengthComputable event) (pos? (.-total event)))
                            (min 100 (max 0 (js/Math.floor (* 100 (/ (.-loaded event) (.-total event))))))) false)))
        (set! (.. xhr -upload -onload) #(progress 100 true)))
      (set! (.-onload xhr) #(reply (result xhr request upload?)))
      (set! (.-onerror xhr) #(reply {:error (if upload? :uncertain :unavailable)}))
      (set! (.-ontimeout xhr) #(reply {:error (if upload? :uncertain :unavailable)}))
      (set! (.-onabort xhr) #(reply {:error (if upload? :uncertain :unavailable)}))
      (if upload?
        (let [data (js/FormData.)]
          (.append data "file" file)
          (.append data "source" "bank")
          (when (contains? #{"," ";"} separator) (.append data "separator" separator))
          (when retry
            (.append data "retry-batch-id" (:id retry))
            (.append data "version" (str (:version retry)))
            (.append data "confirmed" "true"))
          ;; The browser supplies the multipart boundary; never set Content-Type here.
          (.send xhr data))
        (.send xhr))
      (catch :default _ (reply {:error (if upload? :uncertain :unavailable)})))))

(rf/reg-fx :imports/upload #(request! (fn [] (js/XMLHttpRequest.)) rf/dispatch % true))
(rf/reg-fx :imports/page #(request! (fn [] (js/XMLHttpRequest.)) rf/dispatch % false))
(rf/reg-fx :imports/abort (fn [_] (abort!)))

(ns alma-spei.ui.imports.execution-effects
  (:require [alma-spei.common.contracts.execution :as contract]
            [alma-spei.common.contracts.decisions :as decisions]
            [alma-spei.common.validation :as validation]
            [re-frame.core :as rf]))

(defonce pending (atom {}))
(def mutations #{:backup :commit :undo})
(defn abort! [] (doseq [[_ controller] @pending] (.abort controller)) (reset! pending {}))
(defn path [{:keys [op batch-id preview-id offset]}]
  (let [base (str "/api/imports/" batch-id) page (str "?limit=25&offset=" (or offset 0))]
    (case op
      :capabilities "/api/imports/capabilities" :history (str "/api/imports" page)
      :outcome (str base "/outcome") :eligibility (str base "/undo-eligibility" page)
      :applied (str base "/commit-items" page) :preview (str base "/previews/" preview-id)
      :preview-items (str base "/previews/" preview-id "/items" page)
      :backup (str base "/previews/" preview-id "/backup") :commit "/api/imports/commit" :undo "/api/imports/undo-last")))
(defn valid? [data {:keys [op batch-id preview-id offset input]}]
  (and (validation/valid? (case op :capabilities contract/capabilities :history contract/history
                                (:outcome :commit :undo) contract/outcome :eligibility contract/eligibility :applied contract/items-response
                                :preview decisions/preview-response :preview-items decisions/items-response :backup contract/backup-response) data)
       (or (#{:history :capabilities} op) (= batch-id (if (= :preview-items op) (get-in data [:preview :batch-id]) (get-in data [:batch :id]))))
       (case op
         :capabilities (= (:commit-enabled data) (= "synthetic-test-v1" (:mode data)))
         :history (and (= (set (map :created-by (:batches data))) (set (map :id (:uploaders data))))
                       (= (count (:uploaders data)) (count (set (map :id (:uploaders data))))))
         :preview (and (= preview-id (get-in data [:preview :id])) (= batch-id (get-in data [:preview :batch-id])))
         :preview-items (= preview-id (get-in data [:preview :id]))
         :backup (= (:batch-version input) (get-in data [:batch :version]))
         :commit (and (= (:preview-id input) (get-in data [:commit :preview-id])) (= (:effect-digest input) (get-in data [:commit :effect-digest]))
                      (= (:backup-id input) (get-in data [:commit :backup :id])) (#{"committed" "undone"} (get-in data [:batch :state])))
         :undo (and (= (:commit-id input) (get-in data [:commit :id])) (:undo data) (= "undone" (get-in data [:batch :state]))) true)
       (if (#{:history :applied :preview-items :eligibility} op)
         (and (= 25 (:limit data)) (= (or offset 0) (:offset data))
              (= (count (get data (case op :history :batches :eligibility :blockers :items))) (min 25 (max 0 (- (:total data) (or offset 0)))))) true)))
(defn request! [fetch-fn dispatch {:keys [op slot request-id csrf input] :as args}]
  (when-let [old (get @pending slot)] (.abort old))
  (let [controller (js/AbortController.)
        timeout (js/setTimeout #(.abort controller) 120000)
        mutation? (contains? mutations op)
        reply (fn [result] (js/clearTimeout timeout) (when (identical? controller (get @pending slot))
                                                       (swap! pending dissoc slot) (dispatch [:execution/loaded slot request-id op result csrf])))
        options (cond-> {:method (if mutation? "POST" "GET") :credentials "same-origin" :cache "no-store"
                         :signal (.-signal controller) :headers {"Accept" "application/json"}}
                  mutation? (assoc :body (js/JSON.stringify (clj->js input))
                                   :headers {"Accept" "application/json" "Content-Type" "application/json" "X-CSRF-Token" csrf}))]
    (swap! pending assoc slot controller)
    (-> (fetch-fn (path args) (clj->js options))
        (.then (fn [response]
                 (if (.-ok response)
                   (-> (.json response) (.then (fn [body] (let [data (js->clj body :keywordize-keys true)]
                                                            (reply (if (valid? data args) {:data data} {:error (if mutation? :uncertain :invalid-response)})))))
                       (.catch (fn [_] (reply {:error (if mutation? :uncertain :invalid-response)}))))
                   (case (.-status response)
                     401 (reply {:error :unauthenticated}) 403 (reply {:error :forbidden}) 404 (reply {:error :not-found})
                     400 (reply {:error :validation}) 409 (reply {:error :conflict})
                     (reply {:error (if mutation? :uncertain :unavailable)})))))
        (.catch (fn [_] (reply {:error (if mutation? :uncertain :unavailable)}))))))
(rf/reg-fx :execution/request #(request! js/fetch rf/dispatch %))
(rf/reg-fx :execution/abort (fn [_] (abort!)))

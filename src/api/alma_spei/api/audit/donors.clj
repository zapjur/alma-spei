(ns alma-spei.api.audit.donors
  (:require [alma-spei.api.donors.repository :as repository]
            [muuntaja.core :as muuntaja])
  (:import [java.util UUID]))

(def fields (conj repository/profile-fields :is-active))

(defn changes [before after]
  (into {} (keep (fn [field]
                   (when (not= (get before field) (get after field))
                     [field {:before (get before field) :after (get after field)}])) fields)))

(defn record! [tx actor donor action field-changes]
  (let [event (UUID/randomUUID)]
    (repository/query tx ["INSERT INTO donor_audit_events(id,actor_id,donor_id,action) VALUES (?,?,?,?)" event actor donor action])
    (doseq [[field {:keys [before after]}] field-changes]
      (repository/query tx ["INSERT INTO donor_audit_changes(event_id,field_name,before_value,after_value) VALUES (?,?,?::jsonb,?::jsonb)"
                            event (.replace (name field) "-" "_")
                            (slurp (muuntaja/encode "application/json" before))
                            (slurp (muuntaja/encode "application/json" after))]))))

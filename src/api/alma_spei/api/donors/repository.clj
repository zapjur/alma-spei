(ns alma-spei.api.donors.repository
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def profile-fields [:entity-type :name :first-name :street :postal-code :city :province])

(defn query [tx statement]
  (jdbc/execute! tx statement {:builder-fn rs/as-unqualified-kebab-maps}))
(defn one [tx statement] (first (query tx statement)))

(defn find-donor [tx id lock?]
  (one tx [(str "SELECT * FROM donors WHERE id=?" (when lock? " FOR UPDATE")) id]))

(defn list-donors [tx {:keys [search entity-type status sort-by sort-direction limit offset]}]
  (let [where " WHERE (?::text IS NULL OR strpos(lower(name), lower(?)) > 0) AND (?::text IS NULL OR entity_type=?) AND (?='all' OR is_active=?)"
        args [search search entity-type entity-type status (= status "active")]
        ;; SQL fragments come only from these fixed maps, never request text.
        column (get {"name" "lower(name)" "city" "lower(city)" "entity-type" "entity_type"} sort-by "lower(name)")
        direction (get {"asc" "ASC" "desc" "DESC"} sort-direction "ASC")]
    {:donors (query tx (into [(str "SELECT * FROM donors" where " ORDER BY " column " " direction " NULLS LAST, id ASC LIMIT ? OFFSET ?")]
                             (concat args [limit offset])))
     :total (:total (one tx (into [(str "SELECT count(*) AS total FROM donors" where)] args)))}))

(defn create! [tx id actor profile]
  (one tx (into ["INSERT INTO donors(id,entity_type,name,first_name,street,postal_code,city,province,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING *" id]
                (concat (map profile profile-fields) [actor actor]))))

(defn update! [tx id actor profile]
  (one tx (into ["UPDATE donors SET entity_type=?,name=?,first_name=?,street=?,postal_code=?,city=?,province=?,updated_by=?,updated_at=now(),version=version+1 WHERE id=? RETURNING *"]
                (concat (map profile profile-fields) [actor id]))))

(defn payments [tx id limit offset]
  {:donations (query tx ["SELECT id,amount,currency,received_on FROM donations WHERE donor_id=? ORDER BY received_on DESC,id ASC LIMIT ? OFFSET ?" id limit offset])
   :total (:total (one tx ["SELECT count(*) AS total FROM donations WHERE donor_id=?" id]))})

(defn history [tx id limit offset]
  (let [events (query tx ["SELECT id,actor_id,occurred_at,action FROM donor_audit_events WHERE donor_id=? ORDER BY occurred_at DESC,id DESC LIMIT ? OFFSET ?" id limit offset])
        changes (when (seq events)
                  (query tx (into [(str "SELECT event_id,field_name,before_value,after_value FROM donor_audit_changes WHERE event_id IN ("
                                        (str/join "," (repeat (count events) "?")) ") ORDER BY field_name")]
                                  (map :id events))))
        by-event (group-by :event-id changes)]
    {:events (mapv (fn [event]
                     (assoc event :changes (get by-event (:id event) []))) events)
     :total (:total (one tx ["SELECT count(*) AS total FROM donor_audit_events WHERE donor_id=?" id]))}))

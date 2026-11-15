(ns alma-spei.api.catalog
  (:require [alma-spei.api.donors.repository :as repository]))

(defn lock! [tx] (repository/query tx ["SELECT pg_advisory_xact_lock(20971239)"]))
(defn version [tx] (:revision (repository/one tx ["SELECT revision FROM canonical_catalog_state WHERE singleton"])))
(defn check! [tx expected]
  (when-not (= expected (version tx))
    (throw (ex-info "Catalogue changed" {:status 409 :code "stale-catalog" :public-message "Baza darczyńców lub wpłat zmieniła się. Wczytaj i zweryfikuj decyzje ponownie."}))))

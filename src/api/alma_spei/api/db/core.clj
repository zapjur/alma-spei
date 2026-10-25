(ns alma-spei.api.db.core
  (:require [next.jdbc :as jdbc])
  (:import [org.postgresql.ds PGSimpleDataSource]))

(defn datasource [{:keys [host port name user password connect-timeout query-timeout]}]
  (doto (PGSimpleDataSource.)
    (.setServerNames (into-array String [host]))
    (.setPortNumbers (int-array [port]))
    (.setDatabaseName name)
    (.setUser user)
    (.setPassword password)
    (.setConnectTimeout connect-timeout)
    (.setSocketTimeout (+ connect-timeout query-timeout))
    (.setApplicationName "alma-spei")))

(defn ready? [ds query-timeout]
  (try
    (boolean
     (seq (jdbc/execute! ds
                         ["select foundation_version from application_metadata where singleton = true and foundation_version = 1"]
                         {:timeout query-timeout})))
    (catch Exception _ false)))

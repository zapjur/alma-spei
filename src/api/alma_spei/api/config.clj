(ns alma-spei.api.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]))

(def schema
  [:map
   [:environment [:enum :development :test]]
   [:http [:map [:host :string] [:port [:int {:min 1 :max 65535}]]]]
   [:database [:map [:host :string] [:port [:int {:min 1 :max 65535}]]
               [:name [:string {:min 1}]] [:user [:string {:min 1}]]
               [:password [:string {:min 1}]]
               [:connect-timeout [:int {:min 1 :max 30}]]
               [:query-timeout [:int {:min 1 :max 30}]]]]])

(defn- merge-config [defaults overrides]
  (merge defaults overrides
         {:http (merge (:http defaults) (:http overrides))
          :database (merge (:database defaults) (:database overrides))}))

(defn- port [value]
  (try (Long/parseLong value)
       (catch Exception _ (throw (ex-info "Invalid numeric configuration" {})))))

(defn resolve-config
  "Resolve already-read configuration. Secret values never enter exception data."
  [defaults local env read-secret]
  (let [password (get env "ALMA_DB_PASSWORD")
        secret-file (get env "ALMA_DB_PASSWORD_FILE")]
    (when (or (and password secret-file)
              (get-in local [:database :password]))
      (throw (ex-info "Use one explicit secret source" {})))
    (let [config (merge-config defaults local)
          overrides {"ALMA_HTTP_HOST" [:http :host]
                     "ALMA_HTTP_PORT" [:http :port]
                     "ALMA_DB_HOST" [:database :host]
                     "ALMA_DB_PORT" [:database :port]
                     "ALMA_DB_NAME" [:database :name]
                     "ALMA_DB_USER" [:database :user]}
          config (reduce-kv (fn [c key path]
                              (if-let [value (get env key)]
                                (assoc-in c path (if (= :port (last path)) (port value) value))
                                c)) config overrides)
          config (cond-> config
                   (get env "ALMA_ENV") (assoc :environment (keyword (get env "ALMA_ENV"))))
          secret (if secret-file
                   (try (str/trim-newline (read-secret secret-file))
                        (catch Exception _ (throw (ex-info "Cannot read database secret file" {}))))
                   password)
          config (assoc-in config [:database :password] secret)]
      (when-not (m/validate schema config)
        (throw (ex-info "Missing or invalid application configuration" {})))
      (when-not (#{"127.0.0.1" "localhost" "::1"} (get-in config [:http :host]))
        (throw (ex-info "Foundation HTTP listener must use loopback" {})))
      config)))

(defn load-config
  ([] (load-config (System/getenv)))
  ([env]
   (try
     (resolve-config (edn/read-string (slurp (io/resource "config.edn")))
                     (if-let [file (get env "ALMA_CONFIG_FILE")]
                       (edn/read-string (slurp file)) {})
                     env slurp)
     (catch Exception _ (throw (ex-info "Cannot load application configuration; check required settings" {}))))))

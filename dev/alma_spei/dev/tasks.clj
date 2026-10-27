(ns alma-spei.dev.tasks
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.nio.file FileAlreadyExistsException]
           [java.security SecureRandom]
           [java.util Base64]))

(defn init! []
  (fs/create-dirs ".secrets" {:posix-file-permissions "rwx------"})
  (doseq [name ["admin-password" "migration-password" "app-password"]]
    (let [path (fs/path ".secrets" name)]
      (try
        (fs/create-file path {:posix-file-permissions "rw-------"})
        (spit (str path)
              (str (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                                    (.generateSeed (SecureRandom.) 32)) "\n"))
        (catch FileAlreadyExistsException _ nil))))
  (println "Local secret files are ready; existing values were preserved."))

(defn- local-env []
  ;; Preserve explicit caller configuration and avoid introducing a second secret source.
  (cond-> {}
    (not (or (System/getenv "ALMA_DB_PASSWORD")
             (System/getenv "ALMA_DB_PASSWORD_FILE")))
    (assoc "ALMA_DB_PASSWORD_FILE" (str (fs/absolutize ".secrets/app-password")))))

(defn- migration-env []
  (merge (local-env)
         {"ALMA_MIGRATION_USER" (or (System/getenv "ALMA_MIGRATION_USER") "alma_spei_migrator")
          "ALMA_MIGRATION_PASSWORD_FILE" (or (System/getenv "ALMA_MIGRATION_PASSWORD_FILE")
                                             (str (fs/absolutize ".secrets/migration-password")))}))

(defn compose! [& args]
  (apply process/shell "docker" "compose" "-p" "alma-spei" "-f" "infra/compose.yaml" args))

(defn migrate! []
  (process/shell {:extra-env (migration-env)} "clojure" "-Srepro" "-M:migrate" "up"))

(defn start-api! []
  (process/shell {:extra-env (local-env)} "clojure" "-Srepro" "-M:run"))

(defn repl! []
  (process/shell {:extra-env (local-env)} "clojure" "-Srepro" "-M:repl"))

(defn ui! [action]
  (process/shell "clojure" "-Srepro" "-M:ui" "-m" "shadow.cljs.devtools.cli" action "app"))

(defn check! []
  (doseq [args [["-M:lint"] ["-M:format" "check"] ["-M:test"] ["-M:repl:test-repl"]
                ["-M:ui" "-m" "shadow.cljs.devtools.cli" "compile" "test"]
                ["-M:ui" "-m" "shadow.cljs.devtools.cli" "release" "app"]]]
    (apply process/shell "clojure" "-Srepro" args)))

(defn integration! []
  (process/shell {:extra-env (merge (migration-env)
                                    {"ALMA_ENV" "test"
                                     "ALMA_TEST_ADMIN_PASSWORD_FILE"
                                     (or (System/getenv "ALMA_TEST_ADMIN_PASSWORD_FILE")
                                         (str (fs/absolutize ".secrets/admin-password")))})}
                 "clojure" "-Srepro" "-M:integration"))

(defn smoke! []
  (let [base (str "http://127.0.0.1:" (or (System/getenv "ALMA_HTTP_PORT") "3000"))
        get! (fn [path]
               (let [response (http/get (str base path) {:timeout 5000 :throw true :as :string})]
                 (when-not (= 200 (:status response))
                   (throw (ex-info "Unexpected HTTP status" {:path path :status (:status response)})))
                 response))]
    (doseq [path ["/api/health/live" "/api/health/ready"]]
      (when-not (= {"status" "ok"} (json/parse-string (:body (get! path))))
        (throw (ex-info "Unexpected health response" {:path path}))))
    (when-not (str/includes? (:body (get! "/")) "lang=\"pl\"")
      (throw (ex-info "Polish application HTML is missing" {})))
    (get! "/js/app.js")
    (println "HTTP smoke checks passed.")))

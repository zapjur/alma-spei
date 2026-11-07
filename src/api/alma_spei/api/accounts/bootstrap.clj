(ns alma-spei.api.accounts.bootstrap
  (:require [alma-spei.api.auth.service :as service]
            [alma-spei.api.config :as config]
            [alma-spei.api.db.core :as db]))

(defn -main [& args]
  (try
    (when (seq args) (throw (ex-info "Bootstrap accepts no command-line arguments" {})))
    (let [console (System/console)]
      (when-not console (throw (ex-info "Interactive terminal required" {})))
      (let [username (.readLine console "Nazwa użytkownika: " (object-array 0))
            display-name (.readLine console "Nazwa wyświetlana: " (object-array 0))
            result (service/bootstrap! (db/datasource (:database (config/load-config)))
                                       {:username username :display-name display-name})]
        (.printf console "Hasło tymczasowe (wyświetlone tylko raz): %s%n" (object-array [(:temporary-password result)]))))
    (catch Exception _
      (binding [*out* *err*] (println "Nie udało się utworzyć pierwszego administratora. Sprawdź konfigurację, dane i istniejące konta."))
      (System/exit 1))))

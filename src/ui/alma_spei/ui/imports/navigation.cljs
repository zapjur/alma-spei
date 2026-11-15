(ns alma-spei.ui.imports.navigation
  (:require [alma-spei.ui.imports.decision-events :as decisions]
            [alma-spei.ui.imports.execution-events :as execution]
            [re-frame.db :as db]))

(defn operation? [] (or (execution/busy? (:execution @db/app-db)) (= :uploading (get-in @db/app-db [:imports :status]))))
(defn allow-leave! []
  (and (not (operation?))
       (let [state (:review @db/app-db)]
         (or (not (or (decisions/dirty? state) (decisions/busy? state)))
             (js/confirm "Opuścić bieżący wiersz i odrzucić niezapisany szkic? Rozpoczęty zapis na serwerze może zostać dokończony.")))))

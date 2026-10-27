(ns alma-spei.dev.reload
  (:require [clojure.java.io :as io]
            [clojure.tools.namespace.repl :as refresh])
  (:import [java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit]))

(defonce watcher (atom nil))
(defonce reload-lock (Object.))
(defonce status (atom :stopped))
(defonce last-error (atom nil))

(defn- snapshot [dirs]
  (into (sorted-map)
        (for [dir dirs
              file (file-seq (io/file dir))
              :when (and (.isFile file) (re-find #"\.cljc?$" (.getName file)))]
          [(.getCanonicalPath file) [(.lastModified file) (.length file)]])))

(defn reload! []
  (locking reload-lock
    (when-let [{:keys [stop start]} @watcher]
      (try
        (stop)
        (let [result (refresh/refresh)]
          (if (= :ok result)
            (do (start) (reset! last-error nil) (reset! status :ready) (println "Backend reloaded."))
            (do (reset! last-error result) (reset! status :error)
                (println "Reload failed. Fix the source and save again, or call (user/reload)."))))
        (catch Exception error
          (reset! last-error error)
          (reset! status :error)
          (println "Reload failed. Check source/configuration and save again, or call (user/reload)."))))))

(defn stop! []
  (locking reload-lock
    (when-let [{:keys [executor stop]} @watcher]
      (reset! watcher nil)
      (.shutdownNow ^ScheduledExecutorService executor)
      (stop))
    (reset! status :stopped)))

(defn start! [{:keys [dirs stop start]}]
  (locking reload-lock
    (when @watcher (throw (ex-info "Reload watcher is already running" {})))
    (refresh/clear)
    (apply refresh/set-refresh-dirs dirs)
    (let [executor (Executors/newSingleThreadScheduledExecutor
                    (reify ThreadFactory
                      (newThread [_ runnable]
                        (doto (Thread. runnable "alma-spei-reload") (.setDaemon true)))))
          observed (atom (snapshot dirs))
          pending (atom nil)]
      (reset! watcher {:executor executor :stop stop :start start})
      (reload!)
      (.scheduleWithFixedDelay
       executor
       ^Runnable
       (bound-fn []
         (try
           (let [current (snapshot dirs)]
             ;; Wait for two identical polls to avoid loading a partially written file.
             (cond
               (= current @observed) (reset! pending nil)
               (= current @pending) (do (reset! observed current) (reset! pending nil) (reload!))
               :else (reset! pending current)))
           (catch Exception _ (reset! status :error))))
       300 300 TimeUnit/MILLISECONDS)
      :watching)))

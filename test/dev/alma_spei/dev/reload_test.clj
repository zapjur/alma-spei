(ns alma-spei.dev.reload-test
  (:require [alma-spei.api.http.server :as http]
            [alma-spei.dev.reload :as reload]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests]])
  (:import [java.net ServerSocket URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- eventually [predicate]
  (let [deadline (+ (System/nanoTime) 10000000000)]
    (loop []
      (cond
        (predicate) true
        (> (System/nanoTime) deadline) false
        :else (do (Thread/sleep 100) (recur))))))

(deftest save-reloads-dependents-and-recovers-from-invalid-source
  (let [dir (.toFile (Files/createTempDirectory "alma-spei-reload-" (make-array FileAttribute 0)))
        folder (io/file dir "reload_fixture")
        dependency (io/file folder "value.clj")
        handler-file (io/file folder "handler.clj")
        original-loader (.getContextClassLoader (Thread/currentThread))
        loader (clojure.lang.DynamicClassLoader. original-loader)
        port (with-open [socket (ServerSocket. 0)] (.getLocalPort socket))
        running (atom nil)
        starts (atom 0)
        stop (fn [] (when-let [server @running] (http/stop! server) (reset! running nil)))
        start (fn []
                (let [handler (requiring-resolve 'reload-fixture.handler/handler)]
                  (reset! running (http/start! @handler {:host "127.0.0.1" :port port}))
                  (swap! starts inc)))
        write-value (fn [value]
                      (spit dependency (str "(ns reload-fixture.value)\n(def value " (pr-str value) ")\n")))
        body (fn []
               (try
                 (.body (.send (HttpClient/newHttpClient)
                               (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port)))
                                   (.timeout (java.time.Duration/ofSeconds 1)) .GET .build)
                               (HttpResponse$BodyHandlers/ofString)))
                 (catch Exception _ nil)))]
    (.mkdirs folder)
    ;; Add only synthetic fixtures to the runtime classpath. Production sources are untouched.
    (.addURL loader (.toURL (.toURI dir)))
    (.setContextClassLoader (Thread/currentThread) loader)
    (write-value "first")
    (spit handler-file
          "(ns reload-fixture.handler (:require [reload-fixture.value :as value]))\n(defn handler [_] {:status 200 :body value/value})\n")
    (try
      (reload/start! {:dirs [(str dir)] :stop stop :start start})
      (is (= "first" (body)))
      (write-value "second-version")
      (is (eventually #(= "second-version" (body)))
          (str "Saving a dependency updates the captured HTTP handler: " (some-> @reload/last-error .getMessage)))
      (is (= 2 @starts))
      (spit dependency "(ns reload-fixture.value)\n(def value")
      (is (eventually #(= :error @reload/status)))
      (is (nil? @running) "An invalid edit stops the backend without stopping the watcher")
      (write-value "recovered-version")
      (is (eventually #(= "recovered-version" (body))) "A subsequent valid save recovers in the same JVM")
      (reload/stop!)
      (is (= :stopped @reload/status))
      (is (nil? @reload/watcher))
      (is (nil? @running))
      (with-open [socket (ServerSocket. port)]
        (is (= port (.getLocalPort socket)) "Shutdown releases the HTTP port"))
      (finally
        (reload/stop!)
        (.setContextClassLoader (Thread/currentThread) original-loader)
        (doseq [file (reverse (file-seq dir))] (io/delete-file file true))))))

(defn -main [& _args]
  (let [{:keys [fail error]} (run-tests 'alma-spei.dev.reload-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))

(ns alma-spei.api.imports.limits
  (:import [java.io FilterInputStream]))

(def file-bytes (* 5 1024 1024))
(def body-bytes (* 6 1024 1024))
(def rows 50000)
(def cell-characters 4096)
(def zip-entries 128)
(def entry-bytes (* 8 1024 1024))
(def expanded-bytes (* 32 1024 1024))

(defn reject!
  ([code] (reject! 400 code))
  ([status code]
   (throw (ex-info "Import rejected" {:status status :code code :failure-code code
                                      :public-message (if (= status 413) "Plik przekracza limit importu." "Nieprawidłowy format pliku importu.")}))))

(defn bounded-stream [input maximum]
  (let [used (volatile! 0)
        check! (fn [n]
                 (when (pos? n)
                   (vswap! used + n)
                   (when (> @used maximum) (reject! 413 "resource-limit"))))]
    (proxy [FilterInputStream] [input]
      (read
        ([] (if (> @used maximum) -1
                (let [n (.read ^java.io.InputStream input)] (check! (if (neg? n) 0 1)) n)))
        ([buffer] (.read ^java.io.InputStream this buffer 0 (alength ^bytes buffer)))
        ([buffer offset length]
         (cond
           (zero? length) 0
           (> @used maximum) -1
           :else (let [n (.read ^java.io.InputStream input buffer offset (int (min length (inc (- maximum @used)))))]
                   (check! n) n))))
      (skip [n]
        ;; Do not let parser skips bypass accounting.
        (let [buffer (byte-array (int (min 8192 n)))]
          (long (max 0 (.read ^java.io.InputStream this buffer)))))
      (markSupported [] false)
      (reset [] (throw (java.io.IOException. "Reset unsupported"))))))

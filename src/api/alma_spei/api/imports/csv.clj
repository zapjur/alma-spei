(ns alma-spei.api.imports.csv
  (:require [alma-spei.api.imports.limits :as limits]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io])
  (:import [java.io InputStreamReader PushbackReader]
           [java.nio.charset CodingErrorAction StandardCharsets]))

(defn with-rows [file separator consume]
  (when-not (#{"," ";"} separator) (limits/reject! "invalid-structure"))
  (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                  (.onMalformedInput CodingErrorAction/REPORT)
                  (.onUnmappableCharacter CodingErrorAction/REPORT))]
    (with-open [input (io/input-stream file)
                reader (PushbackReader. (InputStreamReader. (limits/bounded-stream input limits/file-bytes) decoder))]
      (let [first-char (.read reader)]
        (when (and (not= first-char -1) (not= first-char 65279)) (.unread reader first-char)))
      (consume (map-indexed (fn [i row] [(inc i) (mapv #(hash-map :value %) row)])
                            (csv/read-csv reader :separator (first separator)))))))

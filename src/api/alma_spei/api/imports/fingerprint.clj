(ns alma-spei.api.imports.fingerprint
  (:import [java.io InputStream IOException]
           [java.security MessageDigest]
           [java.util HexFormat]))

(defn measure
  "Hash bytes with a required upper bound; the caller owns the stream."
  [^InputStream input maximum]
  (when-not (and (integer? maximum) (<= 1 maximum 9007199254740991))
    (throw (IllegalArgumentException. "A positive safe byte limit is required.")))
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 8192)]
    (try
      (loop [total 0]
        (let [n (.read input buffer 0 (int (min 8192 (inc (- maximum total)))))]
          (cond
            (= -1 n) {:file-hash-sha256 (.formatHex (HexFormat/of) (.digest digest)) :byte-count total}
            (= 0 n) (let [value (.read input)]
                      (if (= -1 value)
                        {:file-hash-sha256 (.formatHex (HexFormat/of) (.digest digest)) :byte-count total}
                        (if (= total maximum)
                          (throw (ex-info "Plik przekracza dozwolony rozmiar." {:status 413 :code "file-too-large" :public-message "Plik przekracza dozwolony rozmiar."}))
                          (do (.update digest (unchecked-byte value)) (recur (inc total))))))
            (> (+ total n) maximum) (throw (ex-info "Plik przekracza dozwolony rozmiar." {:status 413 :code "file-too-large" :public-message "Plik przekracza dozwolony rozmiar."}))
            :else (do (.update digest buffer 0 n) (recur (+ total n))))))
      (catch IOException _
        (throw (ex-info "Nie udało się odczytać pliku." {:status 400 :code "file-read-failed" :public-message "Nie udało się odczytać pliku."}))))))

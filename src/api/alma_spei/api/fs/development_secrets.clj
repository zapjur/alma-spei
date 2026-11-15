(ns alma-spei.api.fs.development-secrets
  (:require [alma-spei.api.fs.private-files :as private])
  (:import [java.nio.file Files Path Paths LinkOption StandardOpenOption OpenOption FileAlreadyExistsException]
           [java.security SecureRandom]
           [java.util Base64]))

(def filenames ["admin-password" "migration-password" "app-password" "auth-secret"])
(defn secret [] (str (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) (.generateSeed (SecureRandom.) 32)) "\n"))
(defn existing! [path]
  (private/assert-private! path false)
  (when (zero? (Files/size path)) (private/fail!)))
(defn init! [^Path directory]
  (let [directory (private/ensure-directory! directory)]
    ;; Reject unsafe existing material before creating any missing secret.
    (doseq [name filenames :let [file (.resolve directory name)] :when (Files/exists file private/nofollow)] (existing! file))
    (doseq [name filenames :let [file (.resolve directory name)]]
      (try
        (private/create-file! file)
        (try
          (Files/writeString file (secret) (into-array OpenOption [StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS]))
          (catch Exception _
            (try (Files/deleteIfExists file) (catch Exception _ nil))
            (private/fail!)))
        (catch FileAlreadyExistsException _ (existing! file)))))
  (println "Local secret files are ready; existing values were preserved."))
(defn -main [& args]
  (try
    (when-not (or (empty? args) (and (= 2 (count args)) (= "--directory" (first args))))
      (throw (ex-info "Usage: bb init [--directory <existing-parent>/new-secrets-directory]" {})))
    (init! (Paths/get (or (second args) ".secrets") (make-array String 0)))
    (catch Exception _
      (binding [*out* *err*] (println "Secret initialization failed. Check owner-only directory/file access, unsupported filesystems, symlinks or empty existing files. No existing secret was replaced."))
      (System/exit 1))))

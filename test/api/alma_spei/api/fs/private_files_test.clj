(ns alma-spei.api.fs.private-files-test
  (:require [alma-spei.api.fs.private-files :as private]
            [alma-spei.api.fs.development-secrets :as secrets]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files LinkOption FileAlreadyExistsException OpenOption StandardOpenOption]
           [java.nio.file.attribute PosixFilePermissions FileAttribute AclFileAttributeView AclEntry AclEntryType AclEntryPermission AclEntryFlag UserPrincipal DosFileAttributeView]
           [java.util EnumSet]))

(defn remove-tree! [root]
  (with-open [paths (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
    (doseq [path (reverse (vec (iterator-seq (.iterator paths))))]
      (when-not (Files/isSymbolicLink path)
        (when-let [view (Files/getFileAttributeView path DosFileAttributeView private/nofollow)]
          (when (.isReadOnly (.readAttributes ^DosFileAttributeView view)) (.setReadOnly ^DosFileAttributeView view false))))
      (Files/deleteIfExists path))))
(defn temporary [f]
  (let [root (private/create-temp-directory! "alma-spei-files-test-")]
    (try (f root) (finally (remove-tree! root)))))
(defn text! [path value]
  (Files/writeString path value (into-array OpenOption [StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS])))

(deftest selects-the-actual-supported-view-without-an-os-name-guess
  (is (= :posix (private/supported-kind #{"basic" "posix" "acl"})))
  (is (= :acl (private/supported-kind #{"basic" "acl"})))
  (is (thrown? clojure.lang.ExceptionInfo (private/supported-kind #{"basic"}))))

(deftest private-creation-and-create-new-preserve-existing-data
  (temporary
   (fn [root]
     (let [file (.resolve root "input")]
       (private/create-file! file)
       (is (= root (private/assert-private! root true)))
       (is (= file (private/assert-private! file false)))
       (when (= :posix (private/kind file))
         (is (= "rwx------" (PosixFilePermissions/toString (Files/getPosixFilePermissions root private/nofollow))))
         (is (= "rw-------" (PosixFilePermissions/toString (Files/getPosixFilePermissions file private/nofollow)))))
       (text! file "Synthetic existing bytes")
       (is (thrown? FileAlreadyExistsException (private/create-file! file)))
       (is (= "Synthetic existing bytes" (Files/readString file)))
       (is (thrown? clojure.lang.ExceptionInfo (private/assert-private! file true)))))))

(deftest unsafe-posix-existing-paths-are-rejected-without-chmod
  (temporary
   (fn [root]
     (when (= :posix (private/kind root))
       (let [file (private/create-file! (.resolve root "existing")) permissions (PosixFilePermissions/fromString "rw-r--r--")]
         (text! file "Synthetic preserved content")
         (Files/setPosixFilePermissions file permissions)
         (is (thrown? clojure.lang.ExceptionInfo (private/assert-private! file false)))
         (is (= permissions (Files/getPosixFilePermissions file private/nofollow)))
         (is (= "Synthetic preserved content" (Files/readString file))))))))

(deftest symlink-leaves-cannot-be-secret-directories-or-files
  (temporary
   (fn [root]
     (let [target (private/ensure-directory! (.resolve root "target")) link (.resolve root "link")]
       ;; Windows without symlink privilege still exercises the policy's type rejection above.
       (try
         (Files/createSymbolicLink link target (make-array FileAttribute 0))
         (is (thrown? clojure.lang.ExceptionInfo (private/ensure-directory! link)))
         (is (thrown? FileAlreadyExistsException (private/create-file! link)))
         (is (Files/isDirectory target private/nofollow))
         (catch java.nio.file.FileSystemException _ nil)
         (catch UnsupportedOperationException _ nil))))))

(deftest acl-attributes-never-contain-the-unsupported-posix-attribute
  (temporary
   (fn [root]
     (let [owner (private/current-owner root) attrs (private/attributes :acl owner true)
           entries (.value ^FileAttribute (aget attrs 0))]
       (is (= ["acl:acl"] (mapv #(.name ^FileAttribute %) attrs)))
       (is (private/private-acl? entries owner))
       (is (= #{AclEntryFlag/FILE_INHERIT AclEntryFlag/DIRECTORY_INHERIT} (set (.flags ^AclEntry (first entries)))))
       (is (not (private/private-acl? [] owner)))
       (is (not (private/private-acl? [(private/acl-entry (reify UserPrincipal (getName [_] "Synthetic other principal")) true)] owner)))))))

(deftest acl-policy-rejects-inheritance-only-limited-or-foreign-grants
  (temporary
   (fn [root]
     (let [owner (private/current-owner root) entry (private/acl-entry owner true)
           inherit-only (-> (AclEntry/newBuilder entry) (.setFlags (EnumSet/of AclEntryFlag/INHERIT_ONLY)) .build)
           limited (-> (AclEntry/newBuilder entry) (.setPermissions (EnumSet/of AclEntryPermission/READ_DATA)) .build)
           denied (-> (AclEntry/newBuilder entry) (.setType AclEntryType/DENY) .build)]
       (doseq [entries [[inherit-only] [limited] [denied] [entry denied]]]
         (is (not (private/private-acl? entries owner))))))))

(deftest new-acl-owner-is-set-and-read-back-before-any-data-write
  (temporary
   (fn [root]
     (let [path (private/create-file! (.resolve root "acl-policy")) owner (private/current-owner path)
           stored-owner (atom (reify UserPrincipal (getName [_] "Synthetic initial group owner"))) changes (atom 0) acl-changes (atom 0)
           view (reify AclFileAttributeView
                  (name [_] "acl") (getOwner [_] @stored-owner)
                  (setOwner [_ value] (swap! changes inc) (reset! stored-owner value))
                  (getAcl [_] [(private/acl-entry owner false)]) (setAcl [_ entries] (is (private/private-acl? entries owner)) (swap! acl-changes inc)))]
       ;; A policy adapter test on a real empty file, not a claim of NTFS validation.
       (with-redefs [private/kind (constantly :acl) private/acl-view (constantly view)]
         (is (= path (private/prepare-created! path false)))
         (is (= owner @stored-owner))
         (is (= 1 @changes))
         (is (= 1 @acl-changes))
         (is (= 0 (Files/size path))))))))

(deftest failed-enforcement-cleans-only-the-new-empty-path-and-sanitizes-errors
  (temporary
   (fn [root]
     (let [path (.resolve root "new-empty")]
       (with-redefs [private/prepare-created! (fn [& _] (throw (ex-info "Synthetic private diagnostic" {:private "Synthetic rejected value"})))]
         (try (private/create-file! path)
              (is false "Expected enforcement failure")
              (catch clojure.lang.ExceptionInfo e
                (is (= {:type :alma-spei.api.fs.private-files/unsafe-storage} (ex-data e)))
                (is (not (.contains (.getMessage e) "Synthetic private"))))))
       (is (Files/notExists path private/nofollow))))))

(deftest initialization-preserves-existing-secrets-and-rejects-empty-files
  (temporary
   (fn [root]
     (let [directory (.resolve root "secrets")
           output (with-out-str (secrets/init! directory))
           before (into {} (map (fn [name] [name (Files/readString (.resolve directory name))]) secrets/filenames))]
       (is (= 4 (count before)))
       (is (every? #(re-matches #"[A-Za-z0-9_-]{43}\n" %) (vals before)))
       (is (not-any? #(.contains output %) (vals before)))
       (is (= output (with-out-str (secrets/init! directory))))
       (is (= before (into {} (map (fn [name] [name (Files/readString (.resolve directory name))]) secrets/filenames))))
       (let [existing (.resolve directory "app-password")]
         (with-open [out (Files/newOutputStream existing (into-array OpenOption [StandardOpenOption/TRUNCATE_EXISTING StandardOpenOption/WRITE]))] (.flush out))
         (is (thrown? clojure.lang.ExceptionInfo (secrets/init! directory))))))))

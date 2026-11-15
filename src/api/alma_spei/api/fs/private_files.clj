(ns alma-spei.api.fs.private-files
  "Owner-only application files. JVM-only: Babashka invokes the initialization CLI."
  (:require [clojure.set :as set])
  (:import [java.nio.file Files Path LinkOption FileAlreadyExistsException]
           [java.nio.file.attribute FileAttribute PosixFilePermissions AclFileAttributeView AclEntry AclEntryType AclEntryFlag AclEntryPermission]
           [java.util EnumSet]))

(def nofollow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(defn fail! []
  (throw (ex-info "Private storage is unavailable or its access policy is unsafe. Use an owner-controlled POSIX or Windows ACL filesystem."
                  {:type ::unsafe-storage})))
(defn supported-kind [supports?]
  (cond (supports? "posix") :posix (supports? "acl") :acl :else (fail!)))
(defn kind [^Path path]
  (let [store (Files/getFileStore path)] (supported-kind #(.supportsFileAttributeView store ^String %))))
(defn acl-view [^Path path] (Files/getFileAttributeView path AclFileAttributeView nofollow))
(defn current-owner [^Path path]
  (-> path .getFileSystem .getUserPrincipalLookupService (.lookupPrincipalByName (System/getProperty "user.name"))))
(defn acl-entry [owner directory?]
  (let [builder (-> (AclEntry/newBuilder) (.setType AclEntryType/ALLOW) (.setPrincipal owner)
                    (.setPermissions (EnumSet/allOf AclEntryPermission)))]
    (when directory? (.setFlags builder (EnumSet/of AclEntryFlag/FILE_INHERIT AclEntryFlag/DIRECTORY_INHERIT)))
    (.build builder)))
(defn attributes [storage owner directory?]
  (into-array FileAttribute
              [(case storage
                 :posix (PosixFilePermissions/asFileAttribute (PosixFilePermissions/fromString (if directory? "rwx------" "rw-------")))
                 :acl (let [entries [(acl-entry owner directory?)]]
                        (reify FileAttribute (name [_] "acl:acl") (value [_] entries))))]))
(defn private-acl? [entries owner]
  (and (seq entries)
       (every? #(and (= AclEntryType/ALLOW (.type ^AclEntry %)) (= owner (.principal ^AclEntry %))) entries)
       (some #(and (not (contains? (.flags ^AclEntry %) AclEntryFlag/INHERIT_ONLY))
                   (set/subset? (set (EnumSet/allOf AclEntryPermission)) (set (.permissions ^AclEntry %)))) entries)))
(defn assert-private! [^Path path directory?]
  (try
    (when-not (and (not (Files/isSymbolicLink path))
                   (if directory? (Files/isDirectory path nofollow) (Files/isRegularFile path nofollow))) (fail!))
    (let [owner (current-owner path)]
      (when-not (= owner (Files/getOwner path nofollow)) (fail!))
      (case (kind path)
        :posix (let [permissions (set (Files/getPosixFilePermissions path nofollow))
                     maximum (set (PosixFilePermissions/fromString (if directory? "rwx------" "rw-------")))
                     required (set (PosixFilePermissions/fromString (if directory? "r-x------" "r--------")))]
                 (when-not (and (set/subset? permissions maximum) (set/subset? required permissions)) (fail!)))
        :acl (let [view (acl-view path)]
               (when-not (and view (private-acl? (.getAcl ^AclFileAttributeView view) owner)) (fail!)))))
    path
    (catch Exception _ (fail!))))
(defn parent [^Path path]
  ;; Resolve the caller-selected parent (e.g. macOS /var -> /private/var), not a leaf symlink.
  (.toRealPath (.getParent (.normalize (.toAbsolutePath path))) (make-array LinkOption 0)))
(defn prepare-created! [path directory?]
  (when (= :acl (kind path))
    (let [view (acl-view path) owner (current-owner path)]
      (when-not view (fail!))
      ;; Elevated Windows creation may assign a group owner. Only new empty paths may change owner.
      (when-not (= owner (.getOwner ^AclFileAttributeView view)) (.setOwner ^AclFileAttributeView view owner))
      (.setAcl ^AclFileAttributeView view [(acl-entry owner directory?)])))
  (assert-private! path directory?))
(defn checked-create! [create directory?]
  (let [path (try (create)
                  (catch FileAlreadyExistsException e (throw e))
                  (catch Exception _ (fail!)))]
    (try
      (prepare-created! path directory?)
      (catch Exception _
        ;; Creation was atomic and contained no data; never remove an existing path.
        (try (Files/deleteIfExists path) (catch Exception _ nil))
        (fail!)))))
(defn create-file! [^Path path]
  (let [base (parent path) target (.resolve base (.getFileName path))]
    (checked-create! #(Files/createFile target (attributes (kind base) (current-owner base) false)) false)))
(defn ensure-directory! [^Path path]
  (let [base (parent path) target (.resolve base (.getFileName path))]
    (try
      (checked-create! #(Files/createDirectory target (attributes (kind base) (current-owner base) true)) true)
      (catch FileAlreadyExistsException _ (assert-private! target true)))))
(defn create-temp-directory!
  ([prefix] (create-temp-directory! (.toPath (java.io.File. (System/getProperty "java.io.tmpdir"))) prefix))
  ([^Path base prefix]
   (let [base (.toRealPath base (make-array LinkOption 0))]
     (checked-create! #(Files/createTempDirectory base prefix (attributes (kind base) (current-owner base) true)) true))))

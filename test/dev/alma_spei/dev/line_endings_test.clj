(ns alma-spei.dev.line-endings-test
  (:require [alma-spei.api.fs.private-files :as private]
            [alma-spei.api.fs.private-files-test :as fixture]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files Paths CopyOption]))

(def scripts ["infra/postgres/entrypoint.sh" "infra/postgres/init/01-roles.sh"])
(defn git! [directory & args]
  (let [result (apply shell/sh "git" "-C" (str directory) (concat args [:env (merge (into {} (System/getenv)) {"GIT_CONFIG_NOSYSTEM" "1" "GIT_CONFIG_GLOBAL" (str (.resolve directory "no-global-config"))})]))]
    (when-not (zero? (:exit result)) (throw (ex-info "Temporary Git checkout verification failed" {})))
    result))
(deftest shell-scripts-check-out-as-lf-even-with-autocrlf-enabled
  (fixture/temporary
   (fn [root]
     (let [repo (private/ensure-directory! (.resolve root "repository")) checkout (private/ensure-directory! (.resolve root "checkout"))]
       (doseq [file (cons ".gitattributes" scripts)]
         (let [target (.resolve repo file)]
           (Files/createDirectories (.getParent target) (make-array java.nio.file.attribute.FileAttribute 0))
           (Files/copy (Paths/get file (make-array String 0)) target (make-array CopyOption 0))))
       (git! repo "init" "--quiet")
       (git! repo "config" "core.autocrlf" "true")
       (git! repo "add" ".")
       (git! repo "checkout-index" "--all" (str "--prefix=" (.toString checkout) "/"))
       (doseq [file scripts]
         (let [bytes (Files/readAllBytes (.resolve checkout file))]
           (is (not-any? #(= 13 %) bytes))
           (is (= (seq (Files/readAllBytes (Paths/get file (make-array String 0)))) (seq bytes)))))))))

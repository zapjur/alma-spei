(ns alma-spei.ui.imports.execution-location
  (:require [alma-spei.common.contracts.imports :as imports]
            [alma-spei.common.validation :as validation]
            [clojure.string :as str]))

(defn identifier? [value] (validation/valid? imports/identifier value))
(defn offset? [value] (and (integer? value) (<= 0 value 99999)))
(defn parse [hash]
  (let [parts (str/split (or hash "") #"/" -1)
        [_ mode batch preview offset] parts]
    (when (= "#imports" (first parts))
      (case mode
        "history" (when (and (= 3 (count parts)) (re-matches #"[0-9]{1,5}" batch)) {:mode :history :offset (js/parseInt batch 10)})
        "batch" (when (and (= 4 (count parts)) (identifier? batch) (re-matches #"[0-9]{1,5}" preview)) {:mode :batch :batch-id (str/lower-case batch) :offset (js/parseInt preview 10)})
        "confirm" (when (and (= 5 (count parts)) (identifier? batch) (identifier? preview) (re-matches #"[0-9]{1,5}" offset))
                    {:mode :confirm :batch-id (str/lower-case batch) :preview-id (str/lower-case preview) :offset (js/parseInt offset 10)})
        "reimport" (when (and (= 3 (count parts)) (identifier? batch)) {:mode :reimport :batch-id (str/lower-case batch) :offset 0}) nil))))
(defn fragment [{:keys [mode batch-id preview-id offset]}]
  (case mode
    :history (str "#imports/history/" (or offset 0))
    :batch (str "#imports/batch/" batch-id "/" (or offset 0))
    :confirm (str "#imports/confirm/" batch-id "/" preview-id "/" (or offset 0))
    :reimport (str "#imports/reimport/" batch-id)))
(defn write! [route] (.replaceState js/history nil "" (fragment route)))

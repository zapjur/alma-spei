(ns alma-spei.common.validation
  (:require [malli.core :as m]))

(defn valid? [schema value]
  (m/validate schema value))

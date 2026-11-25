(ns alma-spei.common.contracts.imports
  (:require [alma-spei.common.contracts.source-rows :as source-rows]
            [clojure.string :as str]
            [alma-spei.common.validation :as validation]))

(def safe-maximum 9007199254740991)
(def positive-integer [:int {:min 1 :max safe-maximum}])
(def row-count [:int {:min 0 :max safe-maximum}])
(def identifier [:re #"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"])
(def sha256 [:re #"^[0-9a-f]{64}$"])
(def source [:enum "master" "tax" "bank"])
(def state [:enum "reserved" "staging" "review" "approved" "committed" "failed" "cancelled" "undone"])
(def failure-code [:enum "parse-failed" "unsupported-format" "invalid-structure" "resource-limit" "processing-interrupted"])

(defn basename? [value]
  (boolean (and (string? value) (<= 1 (count value) 255) (not (str/blank? value))
                (not (#{"." ".."} value)) (not (re-find #"[/\\\u0000-\u001f\u007f-\u009f]" value)))))
(def filename [:fn basename?])
(def upload-metadata [:map {:closed true} [:source-type source] [:original-filename filename]])
(def fingerprint [:map {:closed true} [:file-hash-sha256 sha256] [:byte-count row-count]])
(def failure [:map {:closed true} [:version positive-integer] [:failure-code failure-code]])
(def retry-request [:map {:closed true} [:version positive-integer] [:confirmed [:= true]]])

(defn counts-valid? [{:keys [total-rows valid-rows invalid-rows reviewed-rows state]}]
  (let [unknown? (every? nil? [total-rows valid-rows invalid-rows reviewed-rows])
        known? (every? number? [total-rows valid-rows invalid-rows reviewed-rows])]
    (and (or unknown? (and known? (= total-rows (+ valid-rows invalid-rows)) (<= reviewed-rows valid-rows)))
         (or (not (#{"review" "approved" "committed" "undone"} state)) known?)
         (or (not (#{"approved" "committed" "undone"} state)) (and known? (zero? invalid-rows) (= valid-rows reviewed-rows))))))

(def batch
  [:and
   [:map {:closed true}
    [:id identifier] [:file-id identifier] [:attempt-number positive-integer] [:retry-of [:maybe identifier]]
    [:source-type source] [:original-filename filename] [:byte-count positive-integer]
    [:created-by identifier] [:created-at :string] [:updated-at :string] [:version positive-integer] [:state state]
    [:failure-code [:maybe failure-code]] [:parse-version [:maybe :string]] [:mapping-version [:maybe :string]]
    [:total-rows [:maybe row-count]] [:valid-rows [:maybe row-count]] [:invalid-rows [:maybe row-count]] [:reviewed-rows [:maybe row-count]]]
   [:fn counts-valid?]
   [:fn (fn [value] (= (= "failed" (:state value)) (some? (:failure-code value))))]])
(def batch-response [:map {:closed true} [:batch batch]])

(def messages
  {:source-type "Wybierz prawidłowe źródło pliku."
   :original-filename "Nieprawidłowa nazwa pliku. Użyj samej nazwy bez ścieżki."
   :file-hash-sha256 "Nieprawidłowy skrót pliku."
   :byte-count "Nieprawidłowy rozmiar pliku."
   :version "Dane importu zostały zmienione. Odśwież widok."
   :failure-code "Nieprawidłowa przyczyna zakończenia importu."
   :confirmed "Potwierdź ponowne przetwarzanie pliku."})
(defn errors [schema value] (validation/errors schema value messages))

(def source-text [:string {:max 4096}])
(def row-payload
  [:map {:closed true}
   [:date [:maybe :string]] [:sender source-text] [:description source-text]
   [:amount [:maybe :string]] [:currency :nil]
   [:source-date source-text] [:source-amount source-text]])
(def row-messages
  {"invalid-date" "Nieprawidłowa data transakcji."
   "missing-sender" "Brakuje danych nadawcy."
   "invalid-sender" "Dane nadawcy muszą być tekstem."
   "invalid-description" "Opis transakcji musi być tekstem."
   "invalid-amount" "Nieprawidłowa kwota transakcji."
   "invalid-master-name" "Podaj pełną nazwę darczyńcy."
   "invalid-master-profile" "Nieprawidłowe dane darczyńcy."
   "invalid-master-observation" "Nieprawidłowa wartość obserwacji źródłowej."
   "unexpected-master-column" "Nieobsługiwane dane w kolumnie Z."
   "invalid-taxpayer" "Nieprawidłowe dane podatnika."
   "invalid-tax-spouse" "Nieprawidłowe dane małżonka."
   "invalid-tax-purpose" "Nieprawidłowy cel szczegółowy."
   "invalid-tax-amount" "Nieprawidłowa kwota przekazana."})
(defn staged-row-schema [payload]
  [:map {:closed true}
   [:worksheet-index positive-integer] [:row-number positive-integer]
   [:payload payload] [:validation-state [:enum "valid" "invalid"]]
   [:errors [:vector [:map {:closed true} [:code :string] [:message :string]]]]])

(def staged-row (staged-row-schema row-payload))
(def master-staged-payload
  [:map {:closed true}
   [:name {:optional true} source-text]
   [:street {:optional true} source-text]
   [:postal-code {:optional true} source-text]
   [:city {:optional true} source-text]
   [:province {:optional true} source-text]
   [:gender {:optional true} source-text]
   [:observations [:vector {:max 18}
                   [:map {:closed true}
                    [:column [:int {:min 7 :max 24}]]
                    [:year {:optional true} [:int {:min 2021 :max 2025}]]
                    [:source-type [:enum "text" "number" "boolean" "unsupported"]]
                    [:value source-text]]]]])
(def tax-staged-payload
  [:map {:closed true}
   [:taxpayer {:optional true} source-rows/tax-subject]
   [:spouse {:optional true} source-rows/tax-subject]
   [:purpose {:optional true} source-text]
   [:amount [:maybe source-text]]])

(def page-query [:map {:closed true} [:limit {:optional true} [:int {:min 1 :max 100}]]
                 [:offset {:optional true} row-count]])

(defn staged-page-response [payload upload?]
  (cond-> [:map {:closed true} [:batch batch] [:rows [:vector (staged-row-schema payload)]]
           [:total row-count] [:limit positive-integer] [:offset row-count]]
    upload? (conj [:skipped-empty-rows row-count])))

(defn source-response [upload?]
  [:multi {:dispatch #(get-in % [:batch :source-type])}
   ["bank" (staged-page-response row-payload upload?)]
   ["master" (staged-page-response master-staged-payload upload?)]
   ["tax" (staged-page-response tax-staged-payload upload?)]])

(def rows-response (source-response false))
(def upload-response (source-response true))

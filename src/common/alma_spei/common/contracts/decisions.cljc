(ns alma-spei.common.contracts.decisions
  (:require [alma-spei.common.contracts.imports :as imports]
            [alma-spei.common.contracts.donors :as donors]
            [alma-spei.common.domain.donors :as domain]))

(def version imports/positive-integer)
(def expected [:int {:min 0 :max imports/safe-maximum}])
(def currency [:re #"^[A-Z]{3}$"])
(def financial [:map {:closed true} [:currency [:maybe currency]] [:evidence [:string {:max 500}]]
                [:disposition [:enum "unresolved" "new" "existing"]] [:overlap-checked :boolean]])
(def proposal-create [:map {:closed true} [:batch-version version] [:profile donors/create-donor]
                      [:checked-existing [:= true]] [:checked-proposals [:= true]]])
(def proposal-update [:map {:closed true} [:batch-version version] [:version version] [:profile donors/create-donor]])
(defn target-valid? [{:keys [kind donor-id proposal-id target-version financial donation-id donation-version]}]
  (and (case kind
         "unresolved" (every? nil? [donor-id proposal-id target-version financial donation-id donation-version])
         "existing-donor" (and donor-id target-version (nil? proposal-id))
         "new-proposal" (and proposal-id target-version (nil? donor-id)) false)
       (if (= "existing" (:disposition financial))
         (and (= kind "existing-donor") donation-id donation-version)
         (every? nil? [donation-id donation-version]))))
(def decision-fields
  [[:kind [:enum "unresolved" "existing-donor" "new-proposal"]]
   [:donor-id [:maybe imports/identifier]] [:proposal-id [:maybe imports/identifier]] [:target-version [:maybe version]]
   [:financial [:maybe financial]] [:donation-id [:maybe imports/identifier]] [:donation-version [:maybe version]]])
(def decision-input
  [:and (into [:map {:closed true} [:batch-version version] [:version expected]] decision-fields) [:fn target-valid?]])
(def preview-input [:map {:closed true} [:batch-version version]])
(def payments-query [:map {:closed true} [:donor-id imports/identifier]
                     [:limit {:optional true} [:int {:min 1 :max 100}]] [:offset {:optional true} imports/row-count]])
(defn complete? [{:keys [kind financial]}]
  (boolean (and (not= kind "unresolved") financial
                (:currency financial) (domain/nonblank-text? (:evidence financial))
                (contains? #{"new" "existing"} (:disposition financial)) (:overlap-checked financial))))
(def proposal [:map {:closed true} [:id imports/identifier] [:batch-id imports/identifier] [:version version]
               [:profile donors/create-donor] [:created-by imports/identifier] [:updated-by imports/identifier]
               [:created-at :string] [:updated-at :string]])
(def decision
  (into [:map {:closed true} [:version version] [:reviewer-id imports/identifier] [:reviewer-name [:maybe :string]] [:target-label [:maybe :string]] [:reviewed-at :string] [:complete :boolean]] decision-fields))
(def proposal-response [:map {:closed true} [:batch imports/batch] [:proposal proposal]])
(def decision-response [:map {:closed true} [:batch imports/batch] [:row imports/staged-row] [:decision [:maybe decision]]])
(def proposals-response [:map {:closed true} [:batch imports/batch] [:proposals [:vector proposal]]
                         [:total imports/row-count] [:limit version] [:offset imports/row-count]])
(def blocker [:map {:closed true} [:worksheet-index version] [:row-number version] [:code :string]])
(def blockers-response [:map {:closed true} [:batch imports/batch] [:blockers [:vector blocker]]
                        [:total imports/row-count] [:limit version] [:offset imports/row-count]])
(def payment [:map {:closed true} [:id imports/identifier] [:version version] [:amount :string] [:currency currency] [:received-on :string]])
(def payments-response [:map {:closed true} [:batch imports/batch] [:donor-id imports/identifier]
                        [:payments [:vector payment]] [:total imports/row-count] [:limit version] [:offset imports/row-count]])
(def item [:map {:closed true} [:ordinal version]
           [:kind [:enum "new-donor" "existing-donor-reference" "new-payment" "duplicate-reference"]]
           [:target-id [:maybe imports/identifier]] [:worksheet-index [:maybe version]] [:row-number [:maybe version]]
           [:payload [:map-of :keyword :any]]])
(def summary [:map {:closed true} [:new-donors imports/row-count] [:new-payments imports/row-count]
              [:duplicate-references imports/row-count] [:totals [:vector [:map {:closed true} [:currency currency] [:amount :string]]]]])
(def preview [:map {:closed true} [:id imports/identifier] [:batch-id imports/identifier] [:batch-version version]
              [:created-by imports/identifier] [:created-at :string] [:rule-version [:= "review-preview-v1"]]
              [:effect-digest imports/sha256] [:item-count imports/row-count] [:summary summary]])
(def preview-response [:map {:closed true} [:batch imports/batch] [:preview preview] [:stale :boolean]])
(def items-response [:map {:closed true} [:preview preview] [:items [:vector item]] [:total imports/row-count] [:limit version] [:offset imports/row-count]])
(def review-event [:map {:closed true} [:id imports/identifier] [:actor-id imports/identifier] [:actor-name :string] [:occurred-at :string]
                   [:event-type [:enum "proposal-created" "proposal-updated" "decision-saved" "decision-cleared" "preview-created"]]
                   [:proposal-id [:maybe imports/identifier]] [:preview-id [:maybe imports/identifier]]
                   [:worksheet-index [:maybe version]] [:row-number [:maybe version]]
                   [:before-version expected] [:after-version version]
                   [:before [:maybe [:map-of :keyword :any]]] [:after [:map-of :keyword :any]]])
(def events-response [:map {:closed true} [:batch imports/batch] [:events [:vector review-event]]
                      [:total imports/row-count] [:limit version] [:offset imports/row-count]])
(def blocker-messages
  {"invalid-row" "Wiersz zawiera błędy odczytu. Popraw plik źródłowy."
   "missing-decision" "Brakuje pełnej decyzji o darczyńcy i wpłacie."
   "unsupported-amount" "Kwota ujemna wymaga ustalenia zasad zwrotów."
   "stale-target" "Profil lub propozycja zmieniły się. Zweryfikuj decyzję ponownie."
   "inactive-donor" "Wybrany profil jest archiwalny."
   "duplicate-mismatch" "Wskazana wpłata nie odpowiada faktom z tego wiersza."})

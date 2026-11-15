(ns alma-spei.common.contracts.execution
  (:require [alma-spei.common.contracts.imports :as imports]
            [alma-spei.common.contracts.decisions :as decisions]))

(def commit-input [:map {:closed true} [:batch-id imports/identifier] [:batch-version decisions/version]
                   [:preview-id imports/identifier] [:effect-digest imports/sha256] [:catalog-version decisions/version]
                   [:backup-id imports/identifier] [:confirmed [:= true]]])
(def undo-input [:map {:closed true} [:batch-id imports/identifier] [:batch-version decisions/version]
                 [:commit-id imports/identifier] [:confirmed [:= true]]])
(def backup-input [:map {:closed true} [:batch-version decisions/version] [:catalog-version decisions/version]])
(def backup [:map {:closed true} [:id imports/identifier] [:adapter [:= "synthetic-test-v1"]] [:synthetic [:= true]]
             [:completed-at :string] [:expires-at :string]])
(def backup-response [:map {:closed true} [:batch imports/batch] [:backup backup]])
(def commit-receipt [:map {:closed true} [:id imports/identifier] [:ordinal decisions/version] [:actor-id imports/identifier]
                     [:actor-name :string] [:occurred-at :string] [:preview-id imports/identifier] [:effect-digest imports/sha256]
                     [:batch-version decisions/version] [:summary decisions/summary] [:backup backup]])
(def undo-receipt [:map {:closed true} [:id imports/identifier] [:actor-id imports/identifier] [:actor-name :string]
                   [:occurred-at :string] [:batch-version decisions/version] [:reversed-payments imports/row-count] [:archived-donors imports/row-count]])
(def outcome [:map {:closed true} [:batch imports/batch] [:commit [:maybe commit-receipt]] [:undo [:maybe undo-receipt]]])
(def history [:map {:closed true} [:batches [:vector imports/batch]] [:total imports/row-count] [:limit decisions/version] [:offset imports/row-count]])
(def conflict [:map {:closed true} [:code [:enum "not-latest" "already-undone" "not-committed" "changed-donor" "changed-payment" "dependent-records"]]
               [:entity-id [:maybe imports/identifier]]])
(def eligibility [:map {:closed true} [:batch imports/batch] [:commit-id [:maybe imports/identifier]] [:eligible :boolean]
                  [:blockers [:vector conflict]] [:total imports/row-count] [:limit decisions/version] [:offset imports/row-count]])
(def applied-item [:map {:closed true} [:ordinal decisions/version] [:kind :string] [:donor-id imports/identifier]
                   [:donation-id [:maybe imports/identifier]] [:proposal-id [:maybe imports/identifier]]
                   [:worksheet-index [:maybe decisions/version]] [:row-number [:maybe decisions/version]]
                   [:donor-version decisions/version] [:donation-version [:maybe decisions/version]] [:reversed :boolean]
                   [:snapshot [:map-of :keyword :any]]])
(def items-response [:map {:closed true} [:batch imports/batch] [:items [:vector applied-item]] [:total imports/row-count] [:limit decisions/version] [:offset imports/row-count]])
(def conflict-messages {"not-latest" "Można wycofać wyłącznie ostatni zatwierdzony import."
                        "already-undone" "Ten import został już wycofany."
                        "not-committed" "Ten import nie został zatwierdzony."
                        "changed-donor" "Profil zmienił się po imporcie. Wycofanie nie może nadpisać późniejszych zmian."
                        "changed-payment" "Stan wpłaty zmienił się. Sprawdź historię i powiązania."
                        "dependent-records" "Późniejsza praca odwołuje się do danych importu. Wycofanie zostało wstrzymane."})

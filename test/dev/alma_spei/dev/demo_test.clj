(ns alma-spei.dev.demo-test
  (:require [alma-spei.api.db.core :as db]
            [alma-spei.dev.demo :as demo]
            [alma-spei.dev.demo-data :as data]
            [clojure.test :refer [deftest is]]))

(def allowed-target {:environment :development :database {:host "127.0.0.1" :name "alma_spei_dev" :user "alma_spei_app"}})

(deftest deterministic-population-and-independent-manifest
  (let [{:keys [donors payments] :as fixtures} (data/dataset)]
    (is (= fixtures (data/dataset)))
    (is (= 20 (count (set (map :id donors)))))
    (is (= 30 (count (set (map :id payments)))))
    (is (= {"individual" 12 "business" 4 "organization" 4} (frequencies (map :entity-type donors))))
    (is (= 18 (count (filter :is-active donors))))
    (is (= 2650.0000M (reduce + (map :amount payments))))
    (is (every? #(= "PLN" (:currency %)) payments))
    (is (every? (set (map :id donors)) (map :donor-id payments)))
    (is (every? #(.startsWith ^String (:name %) "DANE SYNTETYCZNE — ") donors))
    (is (nil? (demo/validate-data! fixtures)))
    (is (= {:donors 20 :payments 30 :active 18 :archived 2
            :entity-types {"individual" 12 "business" 4 "organization" 4} :total-pln 2650.0000M}
           data/expected))))

(deftest identity-and-financial-counterexamples
  (let [{:keys [donors payments]} (data/dataset)
        [first-profile same-name shared-address] donors
        address-fields [:street :postal-code :city :province]
        grouped (vals (group-by #(select-keys % [:donor-id :amount :currency :received-on]) payments))]
    (is (= (:name first-profile) (:name same-name)))
    (is (not= (:id first-profile) (:id same-name)))
    (is (not= (select-keys first-profile address-fields) (select-keys same-name address-fields)))
    (is (= (select-keys first-profile address-fields) (select-keys shared-address address-fields)))
    (is (not= (:id first-profile) (:id shared-address)))
    (is (= 10 (count (filter #(= 2 (count %)) grouped))))
    (is (= 10 (count (filter #(= 1 (count %)) grouped))))))

(deftest unsafe-targets-are-rejected-before-opening-a-connection
  (let [opened (atom false)]
    (with-redefs [db/datasource (fn [_] (reset! opened true))]
      (doseq [target [(assoc allowed-target :environment :production)
                      (assoc-in allowed-target [:database :host] "remote.example")
                      (assoc-in allowed-target [:database :name] "alma_spei_production")
                      (assoc-in allowed-target [:database :user] "alma_spei_migrator")
                      (assoc allowed-target :environment :test)
                      (-> allowed-target (assoc :environment :test) (assoc-in [:database :name] "alma_spei_test_not-a-generated-id"))]]
        (is (thrown? clojure.lang.ExceptionInfo (demo/seed! target "synthetic.admin")))))
    (is (false? @opened)))
  (is (nil? (demo/check-target! allowed-target)))
  (is (nil? (demo/check-target! (-> allowed-target (assoc :environment :test)
                                    (assoc-in [:database :name] "alma_spei_test_0123456789abcdef0123456789abcdef"))))))

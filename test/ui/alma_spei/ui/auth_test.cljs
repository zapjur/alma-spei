(ns alma-spei.ui.auth-test
  (:require [alma-spei.ui.auth.views :as auth]
            [cljs.test :refer-macros [deftest is]]))

(deftest administration-preserves-current-principal
  (let [administrator {:id "admin" :administrator true}
        staff {:id "staff" :administrator false}
        before {:user administrator :users [] :temporary-password "old"}
        after (auth/result-state before :create {:user staff :temporary-password "new"})]
    (is (= administrator (:user after)))
    (is (= [staff] (:users after)))
    (is (= "new" (:temporary-password after)))
    (is (nil? (:temporary-password (auth/result-state after :accounts {:users [staff]}))))))

(deftest session-and-self-reset
  (let [user {:id "admin" :administrator true}
        before {:user user :users [user] :screen :accounts :csrf "old"}
        reset-state (auth/result-state before :reset {:user user :temporary-password "new"})
        logout-state (auth/result-state before :logout {:user nil :csrf "new"})
        session-state (auth/result-state before :session {:user user :csrf "new"})]
    (is (nil? (:user reset-state)))
    (is (= :home (:screen logout-state)))
    (is (nil? (:users logout-state)))
    (is (= "new" (:csrf session-state)))))

(ns alma-spei.integration.execution-reads-test
  (:require [alma-spei.integration.execution-test :as execution]
            [alma-spei.integration.bank-upload-test :as fixture]
            [alma-spei.integration.decisions-test :as review]
            [alma-spei.api.http.routes :as routes]
            [alma-spei.api.imports.repository :as repository]
            [alma-spei.common.contracts.execution :as contract]
            [alma-spei.common.validation :as validation]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each execution/ready)
(deftest capabilities-are-protected-no-store-typed-read-only-and-default-disabled
  (let [before (mapv execution/count-of ["import_backup_runs" "import_commits" "import_batch_events"])
        enabled (review/call :get "/api/imports/capabilities" nil)]
    (is (= 200 (:status enabled)))
    (is (= {:commit-enabled true :mode "synthetic-test-v1"} (:body enabled)))
    (is (= "no-store" (get-in enabled [:headers "Cache-Control"])))
    (is (= 401 (:status (review/call :get "/api/imports/capabilities" nil {:headers {"accept" "application/json"}}))))
    (is (= 400 (:status (review/call :get "/api/imports/capabilities?force=true" nil))))
    (binding [fixture/*context* (assoc fixture/*context* :handler (routes/handler (review/ds) 2 (dissoc (:settings fixture/*context*) :synthetic-imports)))]
      (is (= {:commit-enabled false :mode "disabled"} (:body (review/call :get "/api/imports/capabilities" nil)))))
    (is (= before (mapv execution/count-of ["import_backup_runs" "import_commits" "import_batch_events"])))))

(deftest uploader-labels-are-current-bounded-and-contain-no-account-secrets
  (let [plan (execution/plan!) batch (:batch plan)
        before (mapv execution/count-of ["import_backup_runs" "import_commits" "import_batch_events"])
        history (review/call :get "/api/imports?limit=1&offset=0" nil)
        outcome (review/call :get (review/path batch "/outcome") nil)]
    (is (= 200 (:status history)))
    (is (validation/valid? contract/history (:body history)))
    (is (= [{:id (str (:actor fixture/*context*)) :display-name "Synthetic Bank Staff"}] (get-in history [:body :uploaders])))
    (is (= "Synthetic Bank Staff" (get-in outcome [:body :uploaded-by-name])))
    (is (= [] (get-in (review/call :get "/api/imports?offset=1" nil) [:body :uploaders])))
    (repository/query (review/ds) ["UPDATE users SET display_name='Synthetic Updated Staff' WHERE id=?" (:actor fixture/*context*)])
    (is (= "Synthetic Updated Staff" (get-in (review/call :get (review/path batch "/outcome") nil) [:body :uploaded-by-name])))
    (is (= before (mapv execution/count-of ["import_backup_runs" "import_commits" "import_batch_events"])))
    (is (= 401 (:status (review/call :get "/api/imports" nil {:headers {"accept" "application/json"}}))))))

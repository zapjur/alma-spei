(ns alma-spei.api.bank-test
  (:require [alma-spei.api.imports.adapters.bank :as bank]
            [alma-spei.api.imports.limits :as limits]
            [alma-spei.common.contracts.imports :as contract]
            [alma-spei.common.validation :as validation]
            [alma-spei.dev.bank-fixtures :as fixture]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn parse-bytes [bytes extension separator]
  (let [file (Files/createTempFile "synthetic-bank-" ".input" (make-array FileAttribute 0))]
    (try
      (Files/write file ^bytes bytes (make-array java.nio.file.OpenOption 0))
      (bank/parse! (.toFile file) extension separator)
      (finally (Files/deleteIfExists file)))))
(defn csv [text separator] (parse-bytes (.getBytes ^String text "UTF-8") "csv" separator))
(defn xlsx [parts] (parse-bytes (fixture/zip-bytes parts) "xlsx" nil))
(defn error-code [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))

(deftest exact-amounts-and-strict-dates
  (doseq [[input expected] [["9999999999999999.9999" "9999999999999999.9999"] ["-2,5000" "-2.5000"] ["+12" "12"]]]
    (is (= expected (bank/amount input))))
  (doseq [input ["0" "NaN" "1e3" "1,234.56" "1.23456" "10000000000000000" "12 PLN"]] (is (nil? (bank/amount input))))
  (is (= "2024-02-29" (bank/text-date "29.02.2024"))))

(deftest csv-preserves-first-record-quotes-and-invalid-rows
  (let [result (csv (str "\uFEFF" fixture/sample-csv "\n2026-02-30;;;0\n") ";") rows (:rows result)]
    (is (= 3 (count rows)))
    (is (= 1 (:skipped-empty-rows result)))
    (is (= [1 2 4] (mapv :row-number rows)))
    (is (= ["valid" "valid" "invalid"] (mapv :validation-state rows)))
    (is (= "12.3400" (get-in rows [0 :payload :amount])))
    (is (nil? (get-in rows [0 :payload :currency])))
    (is (= #{"invalid-date" "missing-sender" "invalid-amount"} (set (:validation-codes (last rows)))))
    (is (every? #(validation/valid? contract/row-payload (:payload %)) rows)))
  (let [row (first (:rows (csv "2026-01-02,\"Synthetic, Sender\",\"Line one\nLine \"\"two\"\"\",12.30" ",")))]
    (is (= "Synthetic, Sender" (get-in row [:payload :sender])))
    (is (= "Line one\nLine \"two\"" (get-in row [:payload :description]))))
  (doseq [[text separator] [[fixture/sample-csv nil] ["" ";"] ["2026-01-02;a;b;1;extra" ";"] ["\"unclosed" ";"]]]
    (is (some? (error-code #(csv text separator)))))
  (is (= "parse-failed" (error-code #(parse-bytes (byte-array [(unchecked-byte 255)]) "csv" ";")))))

(deftest xlsx-preserves-sparse-locators-and-exact-source-lexemes
  (let [result (xlsx (fixture/entries)) rows (:rows result)]
    (is (= [2 3] (mapv :row-number rows)))
    (is (= ["valid" "valid"] (mapv :validation-state rows)))
    (is (= ["12.3400" "-2.50"] (mapv #(get-in % [:payload :amount]) rows)))
    (is (= "2026-01-02" (get-in rows [0 :payload :date])))
    (is (= "Synthetic description" (get-in rows [0 :payload :description]))))
  (doseq [[epoch serial expected] [[false "1" "1900-01-01"] [false "59" "1900-02-28"] [false "61" "1900-03-01"]
                                   [true "0" "1904-01-01"] [true "1" "1904-01-02"]]]
    (let [sheet (str/replace fixture/sample-sheet "46024" serial)]
      (is (= expected (get-in (xlsx (fixture/entries {:sheet sheet :epoch epoch})) [:rows 0 :payload :date])))))
  (doseq [serial ["60" "1.5" "-1" "9999999999999999"]]
    (is (nil? (get-in (xlsx (fixture/entries {:sheet (str/replace fixture/sample-sheet "46024" serial)})) [:rows 0 :payload :date])))))

(deftest unsupported-and-hostile-workbooks
  (doseq [parts [(assoc (fixture/entries) "xl/vbaProject.bin" "synthetic")
                 (update (fixture/entries) "xl/worksheets/sheet1.xml" str/replace "<v>12.3400</v>" "<f>1+1</f><v>2</v>")
                 (update (fixture/entries) "xl/worksheets/sheet1.xml" str/replace "</worksheet>" "<mergeCells><mergeCell ref='A1:B1'/></mergeCells></worksheet>")
                 (update (fixture/entries) "xl/_rels/workbook.xml.rels" str/replace "Target='styles.xml'" "Target='https://example.invalid' TargetMode='External'")
                 (assoc (fixture/entries) "docProps/custom.xml" "<!DOCTYPE x [<!ENTITY leak SYSTEM 'file:///synthetic-secret'>]><x>&leak;</x>")
                 (update (fixture/entries) "xl/worksheets/sheet1.xml" str/replace "r='D2'" "r='E2'")
                 (update (fixture/entries) "xl/workbook.xml" str/replace "</sheets>" "<sheet name='Other' sheetId='2' r:id='rId2'/></sheets>")]]
    (is (some? (error-code #(xlsx parts)))))
  (is (some? (error-code #(parse-bytes (.getBytes "synthetic plain text" "UTF-8") "xlsx" nil))))
  (is (= "unsupported-format" (error-code #(parse-bytes (byte-array [1]) "xls" nil)))))

(deftest parser-limits
  (testing "limits use actual data, independently of declarations"
    (with-redefs [limits/rows 1] (is (some? (error-code #(csv fixture/sample-csv ";")))))
    (with-redefs [limits/cell-characters 4] (is (= "resource-limit" (error-code #(csv fixture/sample-csv ";")))))
    (with-redefs [limits/file-bytes 10] (is (= "resource-limit" (error-code #(csv fixture/sample-csv ";")))))
    (with-redefs [limits/zip-entries 2] (is (= "resource-limit" (error-code #(xlsx (fixture/entries))))))
    (with-redefs [limits/entry-bytes 32] (is (= "resource-limit" (error-code #(xlsx (fixture/entries))))))
    (with-redefs [limits/expanded-bytes 1024] (is (= "resource-limit" (error-code #(xlsx (fixture/entries))))))
    (let [parts (assoc (fixture/entries) "docProps/custom.xml" (str "<x>" (apply str (repeat 100000 "a")) "</x>"))]
      (is (= "resource-limit" (error-code #(xlsx parts)))))))

(deftest numeric-xlsx-exponents-remain-exact-and-resource-safe
  (let [parts (fixture/entries {:sheet (str/replace fixture/sample-sheet "12.3400" "1.234E+1")})]
    (is (= "12.34" (get-in (xlsx parts) [:rows 0 :payload :amount]))))
  (doseq [value ["1e+1000000" "1e-1000000" "NaN" "0" "10000000000000000" "1.00001"]]
    (is (nil? (bank/numeric-amount value)))))

(deftest exceeded-stream-can-be-closed-without-repeated-zero-byte-reads
  (with-open [input (limits/bounded-stream (java.io.ByteArrayInputStream. (byte-array 20)) 3)]
    (is (= "resource-limit" (error-code #(.read input (byte-array 8)))))
    (is (= -1 (.read input (byte-array 8))))
    (is (= -1 (.read input)))
    (is (= 0 (.read input (byte-array 0))))))

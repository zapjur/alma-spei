(ns alma-spei.api.imports.xlsx
  (:require [alma-spei.api.imports.limits :as limits]
            [clojure.string :as str])
  (:import [java.io File]
           [java.util.zip ZipFile]
           [javax.xml.parsers SAXParserFactory]
           [org.xml.sax InputSource Attributes]
           [org.xml.sax.helpers DefaultHandler]
           [org.apache.poi.openxml4j.opc OPCPackage PackageAccess]
           [org.apache.poi.xssf.eventusermodel XSSFReader]
           [org.apache.poi.ss.usermodel DateUtil]))

(defn xml! [input start end text]
  (let [factory (doto (SAXParserFactory/newInstance)
                  (.setNamespaceAware true)
                  (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true)
                  (.setFeature "http://xml.org/sax/features/external-general-entities" false)
                  (.setFeature "http://xml.org/sax/features/external-parameter-entities" false))
        reader (.getXMLReader (.newSAXParser factory))
        handler (proxy [DefaultHandler] []
                  (startElement [_ local _ attrs]
                    (let [values (into {} (for [i (range (.getLength ^Attributes attrs))]
                                            [(.getLocalName ^Attributes attrs i) (.getValue ^Attributes attrs i)]))]
                      (when (some #(> (count %) limits/cell-characters) (vals values)) (limits/reject! 413 "resource-limit"))
                      (start local values)))
                  (endElement [_ local _] (end local))
                  (characters [chars offset length] (text (String. ^chars chars offset length)))
                  (error [_] (limits/reject! "invalid-structure"))
                  (fatalError [_] (limits/reject! "invalid-structure")))]
    (.setProperty reader "http://javax.xml.XMLConstants/property/accessExternalDTD" "")
    (.setProperty reader "http://javax.xml.XMLConstants/property/accessExternalSchema" "")
    (.setContentHandler reader handler)
    (.setErrorHandler reader handler)
    (.parse reader (InputSource. input))))

(defn preflight! [file]
  (when (> (.length ^File file) limits/file-bytes) (limits/reject! 413 "resource-limit"))
  (with-open [zip (ZipFile. ^File file)]
    (let [entries (enumeration-seq (.entries zip)) names (volatile! #{}) total (volatile! 0)]
      (when (> (.size zip) limits/zip-entries) (limits/reject! 413 "resource-limit"))
      (doseq [entry entries]
        (let [name (.getName ^java.util.zip.ZipEntry entry)]
          (when (or (contains? @names name) (str/includes? name "..") (str/includes? name "\\")
                    (str/starts-with? name "/")) (limits/reject! "invalid-structure"))
          (vswap! names conj name)
          (when-not (.isDirectory ^java.util.zip.ZipEntry entry)
            (when-not (or (= name "[Content_Types].xml")
                          (re-matches #"(?:_rels/\.rels|docProps/[^/]+\.xml|xl/(?:workbook\.xml|styles\.xml|sharedStrings\.xml|_rels/workbook\.xml\.rels|theme/[^/]+\.xml|worksheets/[^/]+\.xml))" name))
              (limits/reject! "unsupported-format"))
            ;; Read actual expanded bytes before handing a package to POI.
            (with-open [input (limits/bounded-stream (.getInputStream zip entry) limits/entry-bytes)]
              (let [buffer (byte-array 8192)
                    size (loop [size 0]
                           (let [n (.read input buffer)]
                             (if (neg? n) size
                                 (do (vswap! total + n)
                                     (when (> @total limits/expanded-bytes) (limits/reject! 413 "resource-limit"))
                                     (recur (+ size n))))))]
                (when (and (> size 1024) (> size (* 100 (max 1 (.getCompressedSize ^java.util.zip.ZipEntry entry)))))
                  (limits/reject! 413 "resource-limit"))))
            (with-open [input (.getInputStream zip entry)]
              (xml! input
                    (fn [tag attrs]
                      (when (or (#{"f" "mergeCell" "externalReference"} tag)
                                (= "External" (get attrs "TargetMode"))
                                (some #(re-find #"(?i)macroenabled|vbaproject|externallink" %) (vals attrs)))
                        (limits/reject! "unsupported-format")))
                    (fn [_]) (fn [_])))))))))

(defn strings [reader]
  (if-let [input (.getSharedStringsData ^XSSFReader reader)]
    (with-open [input input]
      (let [items (volatile! []) current (volatile! nil) in-text? (volatile! false)]
        (xml! input
              (fn [tag _]
                (when (= tag "si") (vreset! current (StringBuilder.)))
                (when (= tag "t") (vreset! in-text? true)))
              (fn [tag]
                (when (= tag "t") (vreset! in-text? false))
                (when (= tag "si")
                  (vswap! items conj (str @current))
                  (when (> (count @items) (* 4 limits/rows)) (limits/reject! 413 "resource-limit"))
                  (vreset! current nil)))
              (fn [text]
                (when (and @current @in-text?)
                  (.append ^StringBuilder @current ^String text)
                  (when (> (.length ^StringBuilder @current) limits/cell-characters) (limits/reject! 413 "resource-limit")))))
        @items)) []))

(defn date-styles [reader]
  (if-let [input (.getStylesData ^XSSFReader reader)]
    (with-open [input input]
      (let [formats (volatile! {}) styles (volatile! []) in-xfs? (volatile! false)]
        (xml! input
              (fn [tag attrs]
                (case tag
                  "numFmt" (vswap! formats assoc (Integer/parseInt (get attrs "numFmtId")) (get attrs "formatCode"))
                  "cellXfs" (vreset! in-xfs? true)
                  "xf" (when @in-xfs?
                         (let [id (Integer/parseInt (get attrs "numFmtId" "0"))]
                           (vswap! styles conj (DateUtil/isADateFormat id (get @formats id "")))
                           (when (> (count @styles) limits/rows) (limits/reject! 413 "resource-limit")))) nil))
              (fn [tag] (when (= tag "cellXfs") (vreset! in-xfs? false))) (fn [_]))
        @styles)) [false]))

(defn workbook-epoch [reader]
  (with-open [input (.getWorkbookData ^XSSFReader reader)]
    (let [epoch (volatile! false) sheets (volatile! 0)]
      (xml! input (fn [tag attrs]
                    (when (= tag "workbookPr") (vreset! epoch (contains? #{"1" "true"} (get attrs "date1904"))))
                    (when (= tag "sheet") (vswap! sheets inc))) (fn [_]) (fn [_]))
      (when-not (= @sheets 1) (limits/reject! "invalid-structure"))
      @epoch)))

(defn worksheet [input shared styles]
  (let [rows (volatile! []) number (volatile! 0) cells (volatile! {}) cell (volatile! nil)
        capture? (volatile! false) value (volatile! nil) previous-column (volatile! -1)]
    (xml! input
          (fn [tag attrs]
            (case tag
              "row" (let [n (Long/parseLong (get attrs "r" "0"))]
                      (when-not (< @number n (inc limits/rows)) (limits/reject! "invalid-structure"))
                      (vreset! number n) (vreset! cells {}) (vreset! previous-column -1))
              "c" (let [[_ column row] (re-matches #"([A-D])([1-9][0-9]*)" (get attrs "r" ""))
                        index (when column (- (int (first column)) (int \A)))
                        style (Integer/parseInt (get attrs "s" "0"))]
                    (when-not (and index (= @number (Long/parseLong row)) (> index @previous-column)
                                   (<= 0 style) (< style (count styles))) (limits/reject! "invalid-structure"))
                    (vreset! previous-column index)
                    (vreset! cell {:index index :type (get attrs "t" "n") :date? (get styles style false)})
                    (vreset! value (StringBuilder.)))
              ("v" "t") (vreset! capture? true)
              nil))
          (fn [tag]
            (case tag
              ("v" "t") (vreset! capture? false)
              "c" (let [raw (str @value)
                        text (if (= "s" (:type @cell))
                               (let [i (Integer/parseInt raw)]
                                 (when-not (< -1 i (count shared)) (limits/reject! "invalid-structure"))
                                 (get shared i)) raw)]
                    (vswap! cells assoc (:index @cell) (assoc @cell :value text)) (vreset! cell nil))
              "row" (vswap! rows conj [@number (mapv #(get @cells % {:value ""}) (range 4))])
              nil))
          (fn [text]
            (when (and @cell @capture?)
              (.append ^StringBuilder @value ^String text)
              (when (> (.length ^StringBuilder @value) limits/cell-characters) (limits/reject! 413 "resource-limit")))))
    @rows))

(defn read-rows [file]
  (preflight! file)
  (with-open [package (OPCPackage/open ^File file PackageAccess/READ)]
    (let [reader (XSSFReader. package) epoch (workbook-epoch reader)
          shared (strings reader) styles (date-styles reader) sheets (.getSheetsData reader)]
      (when-not (.hasNext sheets) (limits/reject! "invalid-structure"))
      (let [rows (with-open [input (.next sheets)] (worksheet input shared styles))]
        (when (.hasNext sheets) (limits/reject! "invalid-structure"))
        {:rows rows :epoch epoch}))))

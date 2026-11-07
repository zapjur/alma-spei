(ns alma-spei.dev.bank-fixtures
  "Synthetic OOXML fixtures, independent of the parser implementation."
  (:import [java.io ByteArrayOutputStream]
           [java.util.zip ZipOutputStream ZipEntry]))

(def main-ns "http://schemas.openxmlformats.org/spreadsheetml/2006/main")
(def relationships-ns "http://schemas.openxmlformats.org/package/2006/relationships")
(def office-rel "http://schemas.openxmlformats.org/officeDocument/2006/relationships/")
(def sample-csv "2026-01-02;Synthetic Sender;Synthetic description;12,3400\n03.01.2026;Synthetic Other;;-2.50\n")
(def sample-sheet
  (str "<worksheet xmlns='" main-ns "'><sheetData>"
       "<row r='2'><c r='A2' s='1'><v>46024</v></c><c r='B2' t='inlineStr'><is><t>Synthetic Sender</t></is></c>"
       "<c r='C2' t='s'><v>0</v></c><c r='D2'><v>12.3400</v></c></row>"
       "<row r='3'><c r='A3' t='inlineStr'><is><t>2026-01-03</t></is></c>"
       "<c r='B3' t='inlineStr'><is><t>Synthetic Other</t></is></c><c r='D3'><v>-2.50</v></c></row>"
       "</sheetData></worksheet>"))

(defn entries
  ([] (entries {}))
  ([{:keys [sheet epoch] :or {sheet sample-sheet epoch false}}]
   {"[Content_Types].xml"
    (str "<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'>"
         "<Default Extension='rels' ContentType='application/vnd.openxmlformats-package.relationships+xml'/>"
         "<Default Extension='xml' ContentType='application/xml'/>"
         "<Override PartName='/xl/workbook.xml' ContentType='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml'/>"
         "<Override PartName='/xl/worksheets/sheet1.xml' ContentType='application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml'/>"
         "<Override PartName='/xl/styles.xml' ContentType='application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml'/>"
         "<Override PartName='/xl/sharedStrings.xml' ContentType='application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml'/></Types>")
    "_rels/.rels" (str "<Relationships xmlns='" relationships-ns "'><Relationship Id='rId1' Type='" office-rel "officeDocument' Target='xl/workbook.xml'/></Relationships>")
    "xl/workbook.xml" (str "<workbook xmlns='" main-ns "' xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'>"
                           "<workbookPr date1904='" (if epoch "1" "0") "'/><sheets><sheet name='Synthetic' sheetId='1' r:id='rId1'/></sheets></workbook>")
    "xl/_rels/workbook.xml.rels"
    (str "<Relationships xmlns='" relationships-ns "'><Relationship Id='rId1' Type='" office-rel "worksheet' Target='worksheets/sheet1.xml'/>"
         "<Relationship Id='rId2' Type='" office-rel "styles' Target='styles.xml'/>"
         "<Relationship Id='rId3' Type='" office-rel "sharedStrings' Target='sharedStrings.xml'/></Relationships>")
    "xl/styles.xml" (str "<styleSheet xmlns='" main-ns "'><cellXfs count='2'><xf numFmtId='0'/><xf numFmtId='14'/></cellXfs></styleSheet>")
    "xl/sharedStrings.xml" (str "<sst xmlns='" main-ns "' count='1' uniqueCount='1'><si><t>Synthetic description</t></si></sst>")
    "xl/worksheets/sheet1.xml" sheet}))

(defn zip-bytes [parts]
  (let [out (ByteArrayOutputStream.)]
    (with-open [zip (ZipOutputStream. out)]
      (doseq [[name value] parts]
        (.putNextEntry zip (ZipEntry. name))
        (.write zip (.getBytes ^String value "UTF-8"))
        (.closeEntry zip)))
    (.toByteArray out)))

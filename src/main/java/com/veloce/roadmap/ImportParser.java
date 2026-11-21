package com.veloce.roadmap;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.veloce.common.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.xml.stream.*;
import org.apache.commons.csv.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public class ImportParser {
    public record Row(String location,List<String> cells,List<Integer> formulas) {
        public Row { formulas = formulas == null ? List.of() : formulas; }
        public Row(String location,List<String> cells) { this(location, cells, List.of()); }
    }
    public record Worksheet(String id,String name,String headerLocation,List<String> headers,List<Row> rows,
                            Map<String,Integer> mapping,boolean included,SpreadsheetRoadmap.Role role) {}
    public record Analysis(JsonNode candidate,List<String> warnings,List<Roadmap.Issue> issues,List<String> headers,List<Row> rows,
                           Map<String,Integer> mapping,List<String> locations,String extractedText,List<Worksheet> sheets,
                           List<StudyEstimate> assignedEstimates,List<String> preparationNotes,WorkbookSchedule workbookSchedule) {
        public Analysis {
            sheets = sheets == null ? List.of() : sheets;
            assignedEstimates = assignedEstimates == null ? List.of() : assignedEstimates;
            preparationNotes = preparationNotes == null ? List.of() : preparationNotes;
        }
        public Analysis(JsonNode candidate,List<String> warnings,List<Roadmap.Issue> issues,List<String> headers,List<Row> rows,
                        Map<String,Integer> mapping,List<String> locations,String extractedText,List<Worksheet> sheets,
                        List<StudyEstimate> assignedEstimates,List<String> preparationNotes) {
            this(candidate,warnings,issues,headers,rows,mapping,locations,extractedText,sheets,assignedEstimates,preparationNotes,null);
        }
        Analysis withSchedule(WorkbookSchedule schedule) {
            return new Analysis(candidate,warnings,issues,headers,rows,mapping,locations,extractedText,sheets,
                assignedEstimates,preparationNotes,schedule);
        }
        public Analysis(JsonNode candidate,List<String> warnings,List<Roadmap.Issue> issues,List<String> headers,List<Row> rows,
                        Map<String,Integer> mapping,List<String> locations,String extractedText,List<Worksheet> sheets,
                        List<StudyEstimate> assignedEstimates) {
            this(candidate,warnings,issues,headers,rows,mapping,locations,extractedText,sheets,assignedEstimates,List.of());
        }
        public Analysis(JsonNode candidate,List<String> warnings,List<Roadmap.Issue> issues,List<String> headers,List<Row> rows,
                        Map<String,Integer> mapping,List<String> locations,String extractedText,List<Worksheet> sheets) {
            this(candidate,warnings,issues,headers,rows,mapping,locations,extractedText,sheets,List.of());
        }
    }
    public record StudyEstimate(String nodeId,int minutes,String basis) {}
    static final int NODE_LIMIT=5000;
    private static final int TEXT_LIMIT=1_000_000, CELL_LIMIT=200_000, ROW_LIMIT=10_000, SHEET_LIMIT=20;
    private static final long ENTRY_LIMIT=20L*1024*1024, ZIP_LIMIT=100L*1024*1024;
    private static final Pattern BULLET=Pattern.compile("^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+)(.+)$");
    private static final Pattern HEADING=Pattern.compile("^\\s*(#{1,6})\\s+(.+)$");
    private static final Pattern DURATION=Pattern.compile("(?i)\\s*\\(?\\b(\\d{1,5})\\s*(minutes?|mins?|hours?|hrs?|h)\\b\\)?");
    private final Json json;
    private final Roadmap roadmap;
    private final ObjectMapper input;
    private final SpreadsheetRoadmap spreadsheet = new SpreadsheetRoadmap(this);

    public ImportParser(Json json,Roadmap roadmap) {
        this.json=json; this.roadmap=roadmap;
        input=json.mapper.copy();
        input.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(40)
            .maxStringLength(TEXT_LIMIT).maxDocumentLength(10_485_760).maxNumberLength(40).build());
        input.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        ZipSecureFile.setMaxEntrySize(ENTRY_LIMIT);
        ZipSecureFile.setMaxFileCount(500);
        ZipSecureFile.setMinInflateRatio(0.01);
    }

    public Analysis analyze(String format,byte[] bytes,JsonNode profile) throws IOException {
        try {
            return switch(format) {
                case "json" -> json(text(bytes));
                case "csv" -> csv(text(bytes),profile);
                case "xlsx" -> xlsx(bytes,profile);
                case "pdf" -> pdf(bytes,profile);
                case "md","txt" -> outline(text(bytes),profile,null);
                default -> throw Problem.invalid("Use JSON, XLSX, CSV, Markdown, text, or a text-bearing PDF.");
            };
        } catch (Problem e) { throw e; }
        catch (IOException | IllegalArgumentException | XMLStreamException e) {
            throw Problem.invalid("This "+format.toUpperCase(Locale.ROOT)+" could not be read. Check its format or try pasting the text.");
        }
    }

    private Analysis json(String text) throws IOException {
        String s=text.strip();
        if (s.startsWith("\u0060\u0060\u0060")) {
            int first=s.indexOf('\n'),last=s.lastIndexOf("\u0060\u0060\u0060");
            if (first<0 || last<=first) throw Problem.invalid("Paste the complete JSON object.");
            s=s.substring(first+1,last).strip();
        }
        JsonNode candidate=input.readTree(s);
        if (candidate==null || !candidate.isObject()) throw Problem.invalid("The roadmap JSON must contain one object.");
        if (candidate.path("nodes").size()>NODE_LIMIT) throw Problem.invalid("The roadmap exceeds 5,000 nodes.");
        return new Analysis(candidate,List.of(),issues(candidate),List.of(),List.of(),Map.of(),List.of(),null,List.of());
    }

    private String text(byte[] bytes) throws CharacterCodingException {
        String decoded=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (decoded.startsWith("\ufeff")) decoded=decoded.substring(1);
        if (decoded.indexOf(0)>=0 || decoded.length()>TEXT_LIMIT) throw Problem.invalid("Use a UTF-8 text file with at most 1,000,000 characters.");
        if (decoded.isBlank()) throw Problem.invalid("The file does not contain any text.");
        return decoded;
    }

    private Analysis csv(String text,JsonNode profile) throws IOException {
        List<Row> rows=new ArrayList<>();
        List<String> headers=List.of();
        int cells=0;
        try (var parser=CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(true).get().parse(new StringReader(text))) {
            for (CSVRecord r:parser) {
                var values=r.toList();
                if ((cells+=values.size())>CELL_LIMIT || rows.size()>=ROW_LIMIT || values.size()>100 ||
                    values.stream().anyMatch(v->v.length()>4000)) throw Problem.invalid("The CSV exceeds the supported row, column, or cell size limits.");
                if (headers.isEmpty()) headers=values;
                else rows.add(new Row("CSV row "+r.getRecordNumber(),values));
            }
        }
        return spreadsheet.csv(headers,rows,profile,null);
    }

    private Analysis xlsx(byte[] bytes,JsonNode profile) throws IOException, XMLStreamException {
        if (bytes.length<4 || bytes[0]!='P' || bytes[1]!='K' || bytes[2]!=3 || bytes[3]!=4)
            throw Problem.invalid("The file is not a readable XLSX workbook. Encrypted workbooks are unsupported.");
        inspectZip(bytes);
        var sheets = new ArrayList<Worksheet>();
        WorkbookSchedule schedule;
        int count = 0, cells = 0;
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() > SHEET_LIMIT) throw Problem.invalid("Use a workbook with at most 20 sheets.");
            var formatter = new DataFormatter(Locale.ROOT);
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                if (workbook.isSheetHidden(index) || workbook.isSheetVeryHidden(index)) continue;
                var sheet = workbook.getSheetAt(index);
                var rows = new ArrayList<Row>();
                for (var row : sheet) {
                    if (++count > ROW_LIMIT || row.getLastCellNum() > 100)
                        throw Problem.invalid("Use at most 10,000 rows and 100 columns.");
                    var values = new ArrayList<String>();
                    var formulas = new ArrayList<Integer>();
                    for (int col = 0; col < row.getLastCellNum(); col++) {
                        if (++cells > CELL_LIMIT) throw Problem.invalid("The workbook exceeds 200,000 cells.");
                        Cell cell = row.getCell(col);
                        String value = "";
                        if (cell != null && cell.getCellType() == CellType.FORMULA) {
                            formulas.add(col);
                        } else if (cell != null) {
                            value = formatter.formatCellValue(cell);
                            if (value.length() > 4000) throw Problem.invalid("Workbook cells may contain at most 4,000 characters.");
                        }
                        values.add(value);
                    }
                    if (!formulas.isEmpty() || values.stream().anyMatch(value -> !value.isBlank())) {
                        rows.add(new Row(sheet.getSheetName() + " · row " + (row.getRowNum() + 1), values, formulas));
                    }
                }
                if (!rows.isEmpty()) sheets.add(spreadsheet.worksheet("sheet-" + (index + 1), sheet.getSheetName(), rows));
            }
            schedule = WorkbookSchedule.read(workbook);
        }
        if (sheets.isEmpty()) throw Problem.invalid("The workbook has no visible sheets with data.");
        return spreadsheet.workbook(spreadsheet.classify(sheets), profile).withSchedule(schedule);
    }

    private void inspectZip(byte[] bytes) throws IOException, XMLStreamException {
        long total=0;
        int entries=0;
        Set<String> names=new HashSet<>();
        XMLInputFactory xml=XMLInputFactory.newFactory();
        xml.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        xml.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
        xml.setXMLResolver((publicId,systemId,baseUri,namespace)->{ throw new XMLStreamException("External XML disabled"); });
        try (var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry;(entry=zip.getNextEntry())!=null;) {
                String name=entry.getName().toLowerCase(Locale.ROOT);
                if (++entries>500 || name.startsWith("/") || name.contains("\\") || name.contains(":") ||
                    Arrays.asList(name.split("/")).contains("..") || !names.add(name) ||
                    name.contains("vbaproject") || name.startsWith("xl/externallinks") || name.contains("/embeddings/"))
                    throw Problem.invalid("The workbook contains an unsupported or unsafe archive entry.");
                boolean isXml=name.endsWith(".xml") || name.endsWith(".rels");
                var buffer=isXml?new ByteArrayOutputStream():null;
                long size=0;
                byte[] chunk=new byte[8192];
                for (int read;(read=zip.read(chunk))!=-1;) {
                    size+=read; total+=read;
                    if (size>ENTRY_LIMIT || total>ZIP_LIMIT) throw Problem.invalid("Workbook expansion exceeds the permitted archive limits.");
                    if (isXml) buffer.write(chunk,0,read);
                }
                if (isXml) {
                    var reader=xml.createXMLStreamReader(new ByteArrayInputStream(buffer.toByteArray()));
                    try {
                        int depth=0;
                        while(reader.hasNext()) {
                            int event=reader.next();
                            if (event==XMLStreamConstants.DTD || event==XMLStreamConstants.ENTITY_REFERENCE)
                                throw Problem.invalid("XML document types and entities are unsupported.");
                            if (event==XMLStreamConstants.START_ELEMENT && ++depth>100) throw Problem.invalid("Workbook XML is nested too deeply.");
                            if (event==XMLStreamConstants.END_ELEMENT) depth--;
                            if (event==XMLStreamConstants.START_ELEMENT && name.endsWith(".rels")
                                && "External".equalsIgnoreCase(reader.getAttributeValue(null,"TargetMode"))) {
                                // Plain hyperlink relationships are safe: the importer never dereferences them.
                                String type=reader.getAttributeValue(null,"Type");
                                if (type==null || !type.endsWith("/hyperlink")) throw Problem.invalid("External workbook references are unsupported.");
                            }
                        }
                    } finally { reader.close(); }
                }
            }
        }
        if (!names.contains("[content_types].xml") || !names.contains("xl/workbook.xml"))
            throw Problem.invalid("The archive does not contain a valid XLSX workbook.");
    }

    private Analysis pdf(byte[] bytes,JsonNode profile) throws IOException {
        if (bytes.length<5 || !new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))
            throw Problem.invalid("The uploaded file does not have a PDF signature.");
        try (var doc=Loader.loadPDF(bytes)) {
            if (doc.isEncrypted() || !doc.getCurrentAccessPermission().canExtractContent()) throw Problem.invalid("Use an unencrypted PDF that allows text extraction.");
            if (doc.getNumberOfPages()>100) throw Problem.invalid("PDF imports are limited to 100 pages.");
            if (doc.getDocumentCatalog().getNames()!=null && doc.getDocumentCatalog().getNames().getEmbeddedFiles()!=null)
                throw Problem.invalid("Remove PDF attachments before importing.");
            var result=new StringBuilder();
            var stripper=new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page=1;page<=doc.getNumberOfPages();page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                String text=stripper.getText(doc);
                if (result.length()+text.length()>TEXT_LIMIT) throw Problem.invalid("The PDF contains too much extracted text.");
                result.append('\f').append(text);
            }
            if (result.toString().isBlank()) throw Problem.invalid("No selectable text was found. Scanned PDFs need OCR before import, or paste the text here.");
            return outline(result.toString(),profile,"PDF");
        }
    }

    Analysis remap(Analysis old, JsonNode profile, JsonNode selection) {
        return spreadsheet.remap(old, profile, selection).withSchedule(old.workbookSchedule());
    }

    public Analysis prepare(Analysis analysis,JsonNode profile) {
        return ImportPreparation.prepare(this,analysis,profile);
    }

    Analysis review(Analysis old,JsonNode profile,JsonNode candidate) {
        var reviewed=spreadsheet.review(old,profile,candidate);
        return prepare(new Analysis(reviewed.candidate(),reviewed.warnings(),reviewed.issues(),reviewed.headers(),reviewed.rows(),
            reviewed.mapping(),reviewed.locations(),reviewed.extractedText(),reviewed.sheets(),old.assignedEstimates(),old.preparationNotes(),old.workbookSchedule()),profile);
    }

    public List<StudyEstimate> estimateSuggestions(Analysis analysis, JsonNode candidate) {
        return StudyEstimates.suggest(analysis,candidate);
    }

    public Analysis applyEstimates(Analysis old, JsonNode profile, JsonNode candidate, JsonNode selections) {
        var applied = StudyEstimates.apply(old,candidate,selections);
        var reviewed = spreadsheet.review(old,profile,applied.candidate());
        return new Analysis(reviewed.candidate(),reviewed.warnings(),reviewed.issues(),reviewed.headers(),reviewed.rows(),
            reviewed.mapping(),reviewed.locations(),reviewed.extractedText(),reviewed.sheets(),applied.estimates())
            .withSchedule(old.workbookSchedule());
    }

    List<Roadmap.Issue> issues(JsonNode candidate) {
        return roadmap.issues(candidate);
    }

    private Analysis outline(String text,JsonNode profile,String format) {
        var doc=document(profile); var nodes=(ArrayNode)doc.path("nodes"); var locations=new ArrayList<String>();
        var sections=new ArrayDeque<Map.Entry<Integer,String>>();
        int line=0,page=0;
        String[] pages=format==null?new String[]{text}:text.substring(1).split("\\f",-1);
        for (String part:pages) {
            page++; line=0;
            for (String raw:part.split("\\R")) {
                line++;
                if (raw.isBlank()) continue;
                var heading=HEADING.matcher(raw);
                var bullet=BULLET.matcher(raw);
                boolean isHeading=heading.matches(),isBullet=!isHeading && bullet.matches();
                if (!isHeading && !isBullet) continue;
                if (nodes.size()>=NODE_LIMIT) throw Problem.invalid("The outline exceeds 5,000 proposed nodes.");
                String id="topic-"+(nodes.size()+1);
                String title=isHeading?heading.group(2):bullet.group(1);
                Integer minutes=null;
                var duration=DURATION.matcher(title);
                if (duration.find()) {
                    minutes=Integer.parseInt(duration.group(1))*(duration.group(2).toLowerCase(Locale.ROOT).startsWith("h")?60:1);
                    title=(title.substring(0,duration.start())+title.substring(duration.end())).strip().replaceAll("[–—:·-]$","").strip();
                }
                boolean module=isHeading && minutes==null;
                if (isHeading) while (!sections.isEmpty() && sections.peek().getKey()>=heading.group(1).length()) sections.pop();
                String parent=sections.isEmpty()?null:sections.peek().getValue();
                nodes.add(node(id,title,parent,module?"MODULE":"LESSON",module?Integer.valueOf(0):minutes));
                if (module) sections.push(Map.entry(heading.group(1).length(),id));
                locations.add(format==null?"Line "+line:"PDF page "+page+" · line "+line);
            }
        }
        // Remove empty headings: they do not represent an activity with a known estimate.
        var used=new HashSet<String>(); nodes.forEach(n->{if(!n.path("parentId").isNull()) used.add(n.path("parentId").asText());});
        for (int i=nodes.size()-1;i>=0;i--) {
            var n=nodes.get(i);
            if (n.path("kind").asText().equals("MODULE") && !used.contains(n.path("id").asText())) {
                nodes.remove(i); locations.remove(i);
            }
        }
        var warning=nodes.isEmpty()?"No reliable outline was found. Use the extracted text to add topics manually.":
            "The outline was inferred. Review its topics, add outcomes, and supply any missing estimates before confirming.";
        return new Analysis(doc,List.of(warning),issues(doc),List.of(),List.of(),Map.of(),locations,
            text.replace('\f','\n').substring(0,Math.min(text.length(),32_000)),List.of());
    }

    ObjectNode document(JsonNode profile) {
        var d=json.object().put("schemaVersion",1).put("title",profile.path("targetRole").asText("Imported roadmap")+" roadmap")
            .put("industry",profile.path("industry").asText("")).put("targetRole",profile.path("targetRole").asText(""));
        d.putArray("nodes"); return d;
    }
    ObjectNode node(String id,String title,String parent,String kind,Integer minutes) {
        var n=json.object().put("id",id).put("parentId",parent).put("title",title).put("kind",kind).put("estimatedMinutes",minutes)
            .put("priority","MUST").put("outcome","");
        n.putArray("dependsOn"); n.putArray("resources"); return n;
    }
}

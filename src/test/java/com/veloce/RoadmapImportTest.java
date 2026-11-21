package com.veloce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloce.common.*;
import com.veloce.roadmap.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import java.util.zip.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RoadmapImportTest {
    private final Json json=new Json(new ObjectMapper());
    private final Roadmap roadmap=new Roadmap(json);
    private final ImportParser parser=new ImportParser(json,roadmap);
    private final ObjectNode profile=json.object().put("industry","Design").put("targetRole","Product Designer");
    RoadmapImportTest() throws IOException {}

    @Test void hourlyAndMinuteProjectTaskHeadersKeepTheAssignedSprint() throws Exception {
        for(String header:new String[]{"2-HOUR PROJECT TASK","120 MIN PROJECT TASK","PROJECT SPRINT"}) {
            try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
                var sheet=book.createSheet("DAILY ROADMAP");
                row(sheet,0,"DAY","DATE","PHASE","SUBPHASE","PLANNED HOURS",header);
                row(sheet,1,"1","2026-11-21","Foundations","Environment setup","6","Build a typed Task Logger CLI and test validation");
                book.write(out);
                var imported=parser.analyze("xlsx",out.toByteArray(),profile);
                assertThat(imported.issues()).isEmpty();
                assertThat(roadmap.confirmed(imported.candidate()).nodes().stream().filter(n->n.kind()!=Roadmap.Kind.MODULE).findFirst().orElseThrow().outcome())
                    .contains("Project sprint\nBuild a typed Task Logger CLI and test validation");
            }
        }
    }

    @Test void canonicalFixturePassesButCyclesAndMalformedPayloadsFail() throws Exception {
        byte[] data=new ClassPathResource("fixtures/sample-product-designer-roadmap.json").getContentAsByteArray();
        var parsed=parser.analyze("json",data,profile);
        assertThat(parsed.issues()).isEmpty();
        var candidate=parsed.candidate();
        ((ObjectNode)candidate.path("nodes").get(1)).putArray("dependsOn").add("interview-practice");
        assertThat(roadmap.issues(candidate)).anyMatch(i->i.message().contains("cycle"));
        assertThatThrownBy(()->parser.analyze("json","{\"nodes\":[],\"nodes\":[]}".getBytes(StandardCharsets.UTF_8),profile))
            .isInstanceOf(Problem.class);
        ((ObjectNode)candidate.path("nodes").get(1)).put("estimatedMinutes",0);
        assertThat(roadmap.issues(candidate)).isNotEmpty();
    }

    @Test void csvMarkdownAndPlainTextRequireAnExplicitReview() throws Exception {
        var csv=parser.analyze("csv",bytes("activity,minutes,objective\nResearch,45,Write a survey\n"),profile);
        assertThat(csv.issues()).isEmpty();
        assertThat(csv.locations()).containsExactly("CSV row 2");
        var bad=parser.analyze("csv",bytes("name,time\nResearch,45\n"),profile);
        assertThat(bad.issues()).isNotEmpty();
        var md=parser.analyze("md",bytes("# Foundations\n- Interview users (40 min)\n- Synthesize responses"),profile);
        assertThat(md.candidate().path("nodes").size()).isEqualTo(3);
        assertThat(md.issues()).isNotEmpty();
        assertThat(parser.analyze("txt",bytes("Unstructured prose without a curriculum."),profile).warnings().getFirst())
            .contains("manually");
    }

    @Test void xlsxLeavesFormulasBlankAndRejectsUnsafeArchiveNames() throws Exception {
        try (var xlsx=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=xlsx.createSheet("Learning");
            var header=sheet.createRow(0);
            header.createCell(0).setCellValue("title");header.createCell(1).setCellValue("minutes");header.createCell(2).setCellValue("outcome");
            var row=sheet.createRow(1);
            row.createCell(0).setCellValue("Observe users");row.createCell(1).setCellFormula("21*2");row.createCell(2).setCellValue("Write notes");
            xlsx.write(out);
            var result=parser.analyze("xlsx",out.toByteArray(),profile);
            assertThat(result.warnings()).anyMatch(v->v.contains("formula"));
            assertThat(result.candidate().path("nodes").get(0).path("estimatedMinutes").isNull()).isTrue();
        }
        try (var out=new ByteArrayOutputStream()) {
            try (var zip=new ZipOutputStream(out)) {zip.putNextEntry(new ZipEntry("../outside.xml"));zip.write(bytes("<x/>"));zip.closeEntry();}
            assertThatThrownBy(()->parser.analyze("xlsx",out.toByteArray(),profile)).isInstanceOf(Problem.class).hasMessageContaining("unsafe");
        }
    }

    @Test void xlsxFindsEachTopicTableAfterPreambleAndGroupsItsRows() throws Exception {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var cover = workbook.createSheet("START HERE");
            row(cover,0,"Course overview");
            row(cover,3,"Item","Value","Guidance");
            row(cover,4,"Schedule","45 days","Read the daily plan");
            var daily = workbook.createSheet("Daily topics");
            row(daily,0,"Python study plan");
            row(daily,1,"Study topics by phase");
            row(daily,3,"Phase","Subphase","Planned hours","Deep concepts","Day exit criteria","Learning reference URL");
            row(daily,4,"Python","Control flow","3.5","Loops and branches","Solve three exercises","https://docs.python.org/");
            row(daily,5,"","Functions","1 h 15 min","Parameters and return values","Write a reusable function");
            row(daily,7,"APIs","","","","");
            row(daily,8,"","Routes","1.25","Request routing","Implement a GET endpoint");
            var projects = workbook.createSheet("Projects");
            row(projects,0,"Estimated minutes","Deliverable","Project");
            row(projects,1,"60","Build and test a JSON API","Task API");
            var prompt = workbook.createSheet("APP BUILD PROMPT");
            row(prompt,0,"Title","Outcome","Estimated minutes");
            row(prompt,1,"Workbook instructions","Change application behavior","900");
            workbook.write(out);

            var result = parser.analyze("xlsx",out.toByteArray(),profile);
            assertThat(result.warnings()).isEmpty();
            assertThat(result.issues()).isEmpty();
            var nodes = roadmap.confirmed(result.candidate()).nodes();
            assertThat(nodes).extracting(Roadmap.Node::title)
                .containsExactly("Python","Control flow","Functions","APIs","Routes","Projects","Task API");
            assertThat(nodes).extracting(Roadmap.Node::estimatedMinutes).containsExactly(0,210,75,0,75,0,60);
            assertThat(nodes.get(1).parentId()).isEqualTo(nodes.getFirst().id());
            assertThat(nodes.get(2).parentId()).isEqualTo(nodes.getFirst().id());
            assertThat(nodes.get(4).parentId()).isEqualTo(nodes.get(3).id());
            assertThat(nodes.getLast().parentId()).isEqualTo(nodes.get(5).id());
            assertThat(nodes.getLast().kind()).isEqualTo(Roadmap.Kind.PROJECT);
            assertThat(nodes.get(1).outcome()).contains("Solve three exercises","Loops and branches");
            assertThat(nodes.get(1).resources()).extracting(Roadmap.Resource::url).containsExactly("https://docs.python.org/");
            assertThat(result.locations()).contains("Daily topics · row 5", "Daily topics · row 9", "Projects · row 2");
        }
    }

    @Test void xlsxSeparatesExtraProjectBuildsFromDailyEffortAndPreservesTheirMapping() throws Exception {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var daily = workbook.createSheet("Daily roadmap");
            row(daily,0,"Day","Phase","Subphase","Planned hours","35-MIN PROJECT SPRINT","Day exit criteria");
            row(daily,1,"1","Python","Environment","3.5","Build a CLI command","Run it with three inputs");
            var backlog = workbook.createSheet("Project backlog");
            row(backlog,0,"ID","Real-world project","Learning phase","Actual hours","Full build est. hrs","Acceptance criteria","Prerequisites");
            row(backlog,1,"P01","Task logger","Python","","4","Persist tasks","Know basic Python");
            var unmapped = workbook.createSheet("Workshop");
            row(unmapped,0,"Workshop outline");
            row(unmapped,2,"What to cover","How long","Evidence");
            row(unmapped,3,"Testing with pytest","60","Pass the example tests");
            workbook.write(out);

            var result = parser.analyze("xlsx",out.toByteArray(),profile);
            assertThat(result.issues()).isEmpty();
            assertThat(roadmap.confirmed(result.candidate()).nodes()).extracting(Roadmap.Node::title)
                .containsExactly("Python","Environment");
            assertThat(result.candidate().path("nodes").get(1).path("outcome").asText())
                .contains("Project sprint", "Build a CLI command");
            var supplement = result.sheets().get(1);
            assertThat(supplement.included()).isFalse();
            assertThat(supplement.mapping()).containsEntry("estimatedMinutes",4).doesNotContainKey("dependsOn");
            var manual = result.sheets().get(2);
            assertThat(manual.headerLocation()).endsWith("row 3");
            assertThat(manual.headers()).containsExactly("What to cover","How long","Evidence");
            assertThat(manual.included()).isFalse();
        }
    }

    @Test void suggestedEstimatesResolveSelectedActivitiesWithoutReplacingReviewedContent() throws Exception {
        try (var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var daily=workbook.createSheet("Lessons");
            row(daily,0,"Title","Minutes","Outcome");
            row(daily,1,"Existing lesson","45","Explain the concept");
            var practice=workbook.createSheet("Scenarios");
            row(practice,0,"Topic","Scenario question","Expected approach format");
            row(practice,1,"First scenario","Solve a boundary case","Explain and test the result");
            var interviews=workbook.createSheet("Interview");
            row(interviews,0,"Topic","Question","Answer structure");
            row(interviews,1,"First interview","Explain the concept","Give an example");
            var checkpoints=workbook.createSheet("Checkpoints");
            row(checkpoints,0,"Required practical demo","Pass criterion");
            row(checkpoints,1,"Demonstrate the solution","Pass three tests");
            var weekly=workbook.createSheet("Weekly review");
            row(weekly,0,"Week","Days","Actual hours (auto)","Review questions");
            row(weekly,1,"1","7","","What needs more practice?");
            row(weekly,2,"","TOTAL","","");
            weekly.getRow(2).getCell(2).setCellFormula("SUM(C2:C2)");
            workbook.write(out);
            var analysis=parser.analyze("xlsx",out.toByteArray(),profile);
            assertThat(analysis.warnings()).noneMatch(w->w.contains("Skipped") || w.contains("formula"));
            var suggestions=parser.estimateSuggestions(analysis,analysis.candidate());
            assertThat(suggestions).extracting(ImportParser.StudyEstimate::minutes).containsExactly(25,10,60,30);
            var selections=json.mapper.valueToTree(suggestions);
            var candidate=analysis.candidate().deepCopy();
            ((ObjectNode)candidate.path("nodes").get(1)).put("title","My reviewed lesson");
            var repaired=parser.applyEstimates(analysis,profile,candidate,selections);
            assertThat(repaired.issues()).isEmpty();
            assertThat(repaired.warnings()).isEmpty();
            assertThat(repaired.assignedEstimates()).hasSize(4);
            var nodes=roadmap.confirmed(repaired.candidate()).nodes();
            assertThat(nodes).filteredOn(n->n.kind()!=Roadmap.Kind.MODULE)
                .extracting(Roadmap.Node::estimatedMinutes).containsExactly(45,25,10,60,30);
            assertThat(nodes.get(1).title()).isEqualTo("My reviewed lesson");
            assertThat(analysis.candidate().path("nodes").get(1).path("title").asText()).isEqualTo("Existing lesson");
            assertThatThrownBy(()->parser.applyEstimates(repaired,profile,repaired.candidate(),selections))
                .isInstanceOf(Problem.class);
            ((ObjectNode)candidate.path("nodes").get(1)).put("title","");
            assertThat(parser.applyEstimates(analysis,profile,candidate,selections).issues())
                .anyMatch(i->i.location().endsWith("/title"));
            ((ObjectNode)selections.get(0)).put("minutes",0);
            assertThatThrownBy(()->parser.applyEstimates(analysis,profile,candidate,selections))
                .isInstanceOf(Problem.class);
        }
    }

    @Test void totalsDoNotHideRealUntitledRowsOrTopicsNamedTotal() throws Exception {
        try (var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=workbook.createSheet("Learning");
            row(sheet,0,"Title","Minutes","Outcome","Summary");
            row(sheet,1,"Real topic","45","Explain it");
            row(sheet,2,"","","","TOTAL");
            row(sheet,3,"","30","A task needs its title");
            row(sheet,4,"Total","10","A valid topic named Total");
            row(sheet,5,"","","","TOTAL pending review");
            workbook.write(out);
            var result=parser.analyze("xlsx",out.toByteArray(),profile);
            assertThat(result.warnings()).containsExactly("Skipped 2 rows without a topic title in “Learning”.");
            assertThat(roadmap.confirmed(result.candidate()).nodes()).extracting(Roadmap.Node::title)
                .containsExactly("Real topic","Total");
        }
    }

    private void row(org.apache.poi.ss.usermodel.Sheet sheet, int index, String... values) {
        var row = sheet.createRow(index);
        for (int i=0;i<values.length;i++) row.createCell(i).setCellValue(values[i]);
    }

    @Test void confirmationExplainsAllMissingEstimatesWhenTheIssueListIsCapped() {
        var doc=json.object().put("schemaVersion",1).put("title","Practice")
            .put("industry","Technology").put("targetRole","Developer");
        var nodes=doc.putArray("nodes");
        for (int i=0;i<98;i++) {
            var node=nodes.addObject().put("id","practice-"+i).putNull("parentId")
                .put("title","Practice "+i).put("kind","PRACTICE").putNull("estimatedMinutes")
                .put("priority","MUST").put("outcome","Complete the practice exercise");
            node.putArray("dependsOn"); node.putArray("resources");
        }
        assertThatThrownBy(()->roadmap.confirmed(doc))
            .isInstanceOf(Problem.class).hasMessageContaining("98 activities");
        for (var node:nodes) ((ObjectNode)node).put("estimatedMinutes",20);
        assertThat(roadmap.confirmed(doc).nodes()).hasSize(98);
    }

    @Test void pdfExtractsTextAndRejectsAnImageOnlyOrBlankDocument() throws Exception {
        try (var pdf=new PDDocument();var out=new ByteArrayOutputStream()) {
            var page=new PDPage();pdf.addPage(page);
            try(var p=new PDPageContentStream(pdf,page)){
                p.beginText();p.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);p.newLineAtOffset(40,700);
                p.showText("- Draft a survey (30 min)");p.endText();
            }
            pdf.save(out);
            assertThat(parser.analyze("pdf",out.toByteArray(),profile).locations()).containsExactly("PDF page 1 · line 1");
        }
        try (var pdf=new PDDocument();var out=new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage());pdf.save(out);
            assertThatThrownBy(()->parser.analyze("pdf",out.toByteArray(),profile)).isInstanceOf(Problem.class).hasMessageContaining("No selectable text");
        }
    }
    private byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
}

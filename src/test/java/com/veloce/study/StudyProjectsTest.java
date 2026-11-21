package com.veloce.study;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StudyProjectsTest {
    private final ObjectMapper json=new ObjectMapper();
    private ProjectCatalog.Project project(String id,int number,String status,boolean today) {
        return new ProjectCatalog.Project(id,number,"Project "+number,"Validate a real solution",120,json.createArrayNode(),
            status.equals("DONE")?6:status.equals("IN_PROGRESS")?5:0,status,today);
    }
    @Test void foundationLabReferenceDoesNotAssignTheEntireProjectBacklog() throws Exception {
        String task="Environment & toolchain\n\nProject workstream\nFoundation lab\n\nProject sprint\nBuild Task Logger CLI\n\nProject deliverable\nFoundation lab, separate from P01–P50.\n\nInterview prompt\nCompare environments";
        assertThat(ProjectCatalog.assignment(task)).contains("Foundation lab","Build Task Logger CLI").doesNotContain("P01","P50","Compare environments");
        assertThat(ProjectCatalog.assignment("Project workstream\nP01 Intelligent Data Analyzer; P02 House Price Prediction\n\nProject deliverable\nProof")).contains("P01","P02").doesNotContain("Proof");
        var data=json.readTree("""
            [{"id":"sheet-4","name":"PROJECT BACKLOG","headers":["ID","REAL-WORLD PROJECT","SCOPE / MVP"],
              "mapping":{"id":0,"title":1},"rows":[{"cells":["P01","Data Analyzer","Upload CSV and report missing values"]}]}]
            """);
        var project=ProjectCatalog.backlog(data,new com.veloce.common.Json(json)).getFirst();
        assertThat(project.scope()).isEqualTo("Upload CSV and report missing values");
        assertThat(ProjectCatalog.referenced(project,List.of(ProjectCatalog.assignment(task)))).isFalse();
    }
    @Test void totalsAreWorkspaceWideAndTodayExcludesAlreadyCompletedProjects() {
        var all=List.of(project("one",1,"DONE",true),project("two",2,"IN_PROGRESS",true),
            project("three",3,"BLOCKED",false),project("four",4,"NOT_STARTED",false));
        var summary=StudyProjectsApi.overview(all,LocalDate.of(2026,11,15),"Asia/Calcutta");
        assertThat(summary.total()).isEqualTo(4);
        assertThat(summary.completed()).isEqualTo(1);
        assertThat(summary.pending()).isEqualTo(3);
        assertThat(summary.todayTotal()).isEqualTo(2);
        assertThat(summary.todayPending()).isEqualTo(1);
        assertThat(all.stream().filter(p->StudyProjectsApi.matches(p,"","TODAY")).map(ProjectCatalog.Project::id)).containsExactly("two");
        assertThat(all.stream().filter(p->StudyProjectsApi.matches(p,"","PENDING"))).hasSize(3);
    }
    @Test void searchSupportsStableProjectNumbersAndNeverChangesTheSummaryTotals() {
        var complete=project("artifact",101,"DONE",false);
        assertThat(StudyProjectsApi.matches(complete,"p101","ALL")).isTrue();
        assertThat(StudyProjectsApi.matches(complete," P101 ","DONE")).isTrue();
        assertThat(StudyProjectsApi.matches(complete,"p101","PENDING")).isFalse();
        assertThat(StudyProjectsApi.matches(complete,"validate","ALL")).isTrue();
        assertThat(StudyProjectsApi.overview(List.of(),LocalDate.of(2026,11,15),"UTC").todayPending()).isZero();
    }
    @Test void uploadedBacklogKeepsMetadataAndLinksOnlyWholeProjectIdentifiers() throws Exception {
        var data=json.readTree("""
            [{"id":"sheet-4","name":"PROJECT BACKLOG","included":false,
              "headers":["ID","REAL-WORLD PROJECT","LEVEL","TECH STACK","SCOPE / PROTOTYPE","FULL BUILD EST. HRS"],
              "mapping":{"id":0,"title":1,"estimatedMinutes":5},
              "rows":[{"cells":["P01","Environment Doctor CLI","Beginner","Python, argparse","Build diagnostics","2"]},
                       {"cells":["P02","Converter","Beginner","Python, decimal","Convert units","1.5"]}]}]
            """);
        var rows=ProjectCatalog.backlog(data,new com.veloce.common.Json(json));
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().minutes()).isEqualTo(120);
        assertThat(rows.get(1).minutes()).isEqualTo(90);
        assertThat(rows.getFirst().stack()).isEqualTo("Python, argparse");
        assertThat(rows.getFirst().id()).startsWith("backlog_");
        var renamed=data.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode)renamed.get(0).path("rows").get(0).path("cells")).set(1,json.getNodeFactory().textNode("Renamed project"));
        assertThat(ProjectCatalog.backlog(renamed,new com.veloce.common.Json(json)).getFirst().id()).isEqualTo(rows.getFirst().id());
        assertThat(ProjectCatalog.referenced(rows.getFirst(),List.of("Project workstream: P01 · Environment Doctor CLI"))).isTrue();
        assertThat(ProjectCatalog.referenced(rows.getFirst(),List.of("Project workstream: P010 · Another project"))).isFalse();
        assertThat(ProjectCatalog.estimate("missing","FULL BUILD EST. HRS")).isZero();
    }
}


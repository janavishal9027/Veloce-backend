package com.veloce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloce.common.*;
import com.veloce.study.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StudyRulesTest {
    final Json json=new Json(new ObjectMapper());
    ObjectNode work(String status) { return json.object().put("status",status).put("actualMinutes",30).put("notes","Reviewed the topic"); }
    @Test void workValidationUsesGenericEvidenceAcrossDifferentTechnologies() {
        var design=StudyRules.work(json,work("DONE").put("evidence","Research interview notes"),false);
        assertThat(design.path("status").asText()).isEqualTo("DONE");
        assertThat(design.path("validation").asText()).isEmpty();
        assertThatThrownBy(()->StudyRules.work(json,work("BLOCKED"),false)).isInstanceOf(Problem.class);
        assertThat(StudyRules.work(json,work("BLOCKED").put("blockers","Need a dataset"),false).path("blockers").asText()).isNotBlank();
        assertThatThrownBy(()->StudyRules.work(json,work("SKIPPED").put("skipReason","Unavailable"),false)).isInstanceOf(Problem.class);
        assertThat(StudyRules.work(json,work("SKIPPED").put("actualMinutes",0).put("skipReason","Unavailable"),false).path("status").asText()).isEqualTo("SKIPPED");
        assertThatThrownBy(()->StudyRules.work(json,work("DONE").put("notes",""),false)).isInstanceOf(Problem.class);
        assertThatThrownBy(()->StudyRules.work(json,work("IN_PROGRESS").put("actualMinutes",1441),false)).isInstanceOf(Problem.class);
        assertThatThrownBy(()->StudyRules.work(json,work("DONE").put("confidence",6),false)).isInstanceOf(Problem.class);
    }
    @Test void reportsRequireSavedPlannedActivitiesAndKeepActualMinutesSeparate() {
        var date=LocalDate.of(2026,11,14);
        var a=new StudyStore.Record("WORK","date/design","design",date,1,work("DONE"),"now");
        var b=new StudyStore.Record("WORK","date/practice","practice",date,1,work("NOT_STARTED"),"now");
        assertThat(StudyRules.reportIssues(List.of(a),List.of("design","practice"))).hasSize(1);
        assertThat(StudyRules.reportIssues(List.of(a,b),List.of("design","practice"))).hasSize(1);
        var skipped=new StudyStore.Record("WORK","date/practice","practice",date,2,
            work("SKIPPED").put("actualMinutes",0).put("skipReason","Need a reference"),"now");
        assertThat(StudyRules.reportIssues(List.of(a,skipped),List.of("design","practice"))).isEmpty();
        assertThat(a.data().path("actualMinutes").asInt()).isEqualTo(30);
        assertThat(StudyRules.reportIssues(List.of(),List.of())).isNotEmpty();
    }
    @Test void practiceTimerCapsIdleTimeAndRequiresAnswersAndActiveMinutes() {
        Instant start=Instant.parse("2026-11-14T10:00:00Z");
        var data=json.object().put("status","ACTIVE").put("activeSince",start.toString())
            .put("lastSeenAt",start.toString()).put("usedSeconds",0).put("targetMinutes",5);
        data.putArray("questions");
        assertThat(StudyPracticeApi.elapsed(data,start.plusSeconds(600))).isEqualTo(90);
        for(int i=0;i<3;i++)data.withArray("questions").addObject().putObject("answer").put("text","Answer");
        assertThat(StudyPracticeApi.canFinish(data,start.plusSeconds(600))).isFalse();
        data.put("lastSeenAt",start.plusSeconds(300).toString());
        assertThat(StudyPracticeApi.canFinish(data,start.plusSeconds(300))).isTrue();
        data.put("status","PAUSED").put("usedSeconds",300);
        assertThat(StudyPracticeApi.elapsed(data,start.plusSeconds(3600))).isEqualTo(300);
    }
    @Test void malformedProviderGradesAreRejectedAndCsvCellsCannotBecomeFormulas() {
        var result=json.object().put("question","How would you validate the result?").put("feedback","Clear explanation")
            .put("improvement","Describe a boundary case").put("correctness",3).put("reasoning",3).put("clarity",4).put("coverage",2);
        StudyCoach.validate(result);
        result.put("correctness",5);
        assertThatThrownBy(()->StudyCoach.validate(result)).isInstanceOf(Problem.class);
        assertThat(StudyRules.csv("=HYPERLINK(\"https://example.test\")")).startsWith("\"'=");
        assertThat(StudyRules.csv("ordinary, text")).isEqualTo("\"ordinary, text\"");
        assertThatThrownBy(()->StudyPreferences.model("../../secrets")).isInstanceOf(Problem.class);
        assertThatThrownBy(()->StudyPreferences.provider("CUSTOM_URL")).isInstanceOf(Problem.class);
    }
}

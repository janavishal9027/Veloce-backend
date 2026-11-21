package com.veloce.study;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.veloce.common.Json;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DailySectionsTest {
    @org.junit.jupiter.api.Test void minuteAlignedLeaveDoesNotLoseAnotherMinuteAfterCheckInWithFractionalSeconds() {
        var start=java.time.Instant.parse("2026-11-20T19:04:51.550562Z");
        var end=start.plusSeconds(70*60);
        var slots=java.util.List.of(new StudyCarryover.Slot("topic",start,end,70,false));
        var leave=new java.time.Instant[]{java.time.Instant.parse("2026-11-20T19:09:00Z"),java.time.Instant.parse("2026-11-20T19:24:00Z")};
        assertThat(StudyCarryover.capacity(slots,start,end,java.util.Collections.singletonList(leave))).isEqualTo(55);
        assertThat(StudyCarryover.capacity(slots,start,end,java.util.List.of())).isEqualTo(70);
    }
    final Json json=new Json(new ObjectMapper());
    static final String OUTCOME="Concepts\nTransactions and rollback\nTheory deliverable\nDraw a transaction diagram\nCoding practice\nTest an atomic order update\nScenario and edge cases\nHandle duplicate orders\nProject workstream\nP01 Order API\nInterview prompt\nExplain isolation";
    @Test void importedDayTasksBecomeSixSeparateSectionsWithoutLeakingOtherTasks() {
        var cards=DailySections.cards(Map.of("id","day-1","title","Transactions","kind","LESSON","outcome",OUTCOME));
        assertThat(cards).hasSize(6);
        assertThat(DailySections.cards(Map.of("id","day-1","title","Transactions","outcome",OUTCOME),90).stream().mapToInt(c->((Number)c.get("minutes")).intValue()).sum()).isEqualTo(90);
        assertThat(cards.get(0).get("outcome").toString()).contains("Transactions and rollback","Draw a transaction diagram").doesNotContain("duplicate orders","P01");
        assertThat(cards.get(2).get("outcome")).isEqualTo("Scenario and edge cases\nHandle duplicate orders");
        assertThat(cards.get(5).get("outcome").toString()).contains("next study steps");
        assertThat(DailySections.cards(Map.of("title","Read a chapter","outcome","Review the notes","kind","LESSON"))).hasSize(1);
    }
    @Test void combinedLegacyWorkIsRetainedAndMinutesAreNotCountedTwice() {
        var old=json.object().put("status","DONE").put("actualMinutes",25).put("notes","Original work");
        var update=json.object().put("status","PARTIAL").put("actualMinutes",10).put("notes","Implemented order tests");
        var merged=DailySections.merge(json,old,"BASIC_CODING",update,"Transactions");
        assertThat(merged.path("actualMinutes").asInt()).isEqualTo(35);
        assertThat(merged.path("legacyWork")).isEqualTo(old);
        assertThat(merged.path("dailySections").path("CONCEPT").path("notes").asText()).isEqualTo("Original work");
        assertThat(old.has("dailySections")).isFalse();
        var second=DailySections.merge(json,merged,"BASIC_CODING",update.deepCopy().put("actualMinutes",15),"Transactions");
        assertThat(second.path("actualMinutes").asInt()).isEqualTo(40);
        assertThat(second.path("dailySections").path("BASIC_CODING").path("sectionVersion").asInt()).isEqualTo(2);
    }
    @Test void fiveCompletedDomainsCompleteTheDayAndReviewIsOptional() {
        var data=json.object();
        for(String section:DailySections.TITLES.keySet())if(!section.equals("REVIEW"))data=DailySections.merge(json,data,section,
            json.object().put("status","DONE").put("actualMinutes",10).put("notes","Completed").put("evidence","Verified output")
                .put("validation","3 tests pass").put("confidence",4).put("complexity","O(n)").put("edgeCases","Empty input").put("repository","commit abc"),"Daily work");
        assertThat(data.path("status").asText()).isEqualTo("DONE");
        assertThat(data.path("actualMinutes").asInt()).isEqualTo(50);
        assertThat(DailySections.issues(data)).isEmpty();
        ((com.fasterxml.jackson.databind.node.ObjectNode)data.path("dailySections").path("SCENARIO")).put("edgeCases","");
        assertThat(DailySections.issues(data)).anyMatch(s->s.contains("edge cases"));
    }
    @Test void anExplicitExceptionAllowsAnHonestSkippedDayAndMissingDomainsStillBlockReports() {
        var data=json.object();
        for(String section:DailySections.TITLES.keySet())if(!section.equals("REVIEW"))data=DailySections.merge(json,data,section,
            json.object().put("status","NOT_STARTED").put("skipReason","Illness; could not study").put("actualMinutes",0),"Daily work");
        assertThat(data.path("status").asText()).isEqualTo("SKIPPED");
        assertThat(DailySections.issues(data)).isEmpty();
        ((com.fasterxml.jackson.databind.node.ObjectNode)data.path("dailySections")).remove("INTERVIEW");
        assertThat(DailySections.issues(data)).anyMatch(s->s.contains("Interview"));
    }
    @Test void overlappingLeaveIntervalsAreCountedOnceAndBreaksAreExcluded() {
        var start=Instant.parse("2026-11-20T12:30:00Z");
        var slots=List.of(new StudyCarryover.Slot("a",start,start.plusSeconds(3600),60,false),
            new StudyCarryover.Slot("b",start.plusSeconds(4200),start.plusSeconds(7800),60,false));
        var blocked=List.of(new Instant[]{start.plusSeconds(1200),start.plusSeconds(3000)},new Instant[]{start.plusSeconds(2400),start.plusSeconds(4800)});
        assertThat(StudyCarryover.capacity(slots,start,start.plusSeconds(7800),blocked)).isEqualTo(70);
        assertThat(StudyCarryover.capacity(slots,start.plusSeconds(8000),start.plusSeconds(7800),blocked)).isZero();
    }
    @Test void bulkCarryoverIdentityIsScopedToRoadmapDateAndSection() {
        var roadmap=UUID.randomUUID();var day=LocalDate.parse("2026-11-20");
        assertThat(StudyCarryover.bulkKey(roadmap,day,"topic","CONCEPT")).isEqualTo(StudyCarryover.bulkKey(roadmap,day,"topic","CONCEPT"))
            .isNotEqualTo(StudyCarryover.bulkKey(roadmap,day,"topic","PROJECT"))
            .isNotEqualTo(StudyCarryover.bulkKey(UUID.randomUUID(),day,"topic","CONCEPT"));
    }
}

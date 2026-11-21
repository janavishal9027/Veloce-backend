package com.veloce.study;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.veloce.common.Problem;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StudyHistoryTest {
    private final LocalDate start=LocalDate.of(2026,11,1),today=LocalDate.of(2026,11,15);

    @Test void historyNeverExtendsBeforePreparationOrBeyondToday() {
        assertThat(StudyHistoryApi.range(start,today,"2026-10-01","2026-12-31"))
            .isEqualTo(new StudyHistoryApi.Range(start,today));
        assertThat(StudyHistoryApi.range(start,today,null,null))
            .isEqualTo(new StudyHistoryApi.Range(start,today));
        assertThat(StudyHistoryApi.range(start,today,"2026-10-01","2026-10-30").empty()).isTrue();
        assertThat(StudyHistoryApi.range(today.plusDays(3),today,null,null).empty()).isTrue();
        assertThatThrownBy(()->StudyHistoryApi.range(start,today,"2026-11-12","2026-11-01"))
            .isInstanceOf(Problem.class);
    }
    @Test void paginationCannotAdvanceItsSnapshotIntoTheFuture() {
        var now=Instant.parse("2026-11-15T00:00:00Z");
        assertThat(StudyHistoryApi.cutoff("2026-11-16T00:00:00Z",now)).isEqualTo(now);
        assertThat(StudyHistoryApi.cutoff("2026-11-14T20:00:00Z",now))
            .isEqualTo(Instant.parse("2026-11-14T20:00:00Z"));
        assertThatThrownBy(()->StudyHistoryApi.cutoff("not-a-date",now)).isInstanceOf(Problem.class);
    }
    @Test void revisionComparisonIncludesRemovedEvidenceAndChangedEffort() {
        var json=new ObjectMapper();
        var earlier=json.createObjectNode().put("notes","First solution").put("actualMinutes",20)
            .put("evidence","Original result").put("activityTitle","Old title");
        var current=json.createObjectNode().put("notes","Revised solution").put("actualMinutes",35)
            .put("activityTitle","New title");
        assertThat(StudyHistoryApi.changes(earlier,current))
            .containsExactly("actualMinutes","evidence","notes");
        assertThat(StudyHistoryApi.changes(null,current)).containsExactly("actualMinutes","notes");
    }
}


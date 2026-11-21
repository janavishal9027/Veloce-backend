package com.veloce.study;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.veloce.common.*;
import com.veloce.workspace.WorkspaceStore;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="VELOCE_UI_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:5432/veloce_design_verify_261115")
class StudyViewPostgresTest {
    @SuppressWarnings("unchecked")
    @Test void realSummarySqlPreservesSnapshotVersionsAndSplitsFocusAcrossMidnight() {
        var source=new DriverManagerDataSource(System.getenv("VELOCE_UI_TEST_DB_URL"),System.getenv("VELOCE_UI_TEST_DB_USER"),System.getenv("VELOCE_UI_TEST_DB_PASSWORD"));
        var json=new Json(new ObjectMapper());
        var api=new StudyViewApi(new StudyStore(new WorkspaceStore(new JdbcTemplate(source),json),Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"),ZoneOffset.UTC)));
        var owner=UUID.fromString("10000000-0000-0000-0000-000000000001");
        var view=api.view(owner,UUID.fromString("20000000-0000-0000-0000-000000000001"));
        var totals=(Map<String,Object>)view.get("totals");
        assertThat(totals).containsEntry("studyDays",3).containsEntry("plannedMinutes",180).containsEntry("reportedMinutes",60)
            .containsEntry("completeDays",1).containsEntry("focusSeconds",3600).containsEntry("projectCount",2);
        var days=(List<Map<String,Object>>)view.get("days");
        assertThat(days.getFirst()).containsEntry("focusSeconds",1800).containsEntry("reportVersion",2L).containsEntry("reportFresh",false);
        assertThat(days.get(1)).containsEntry("focusSeconds",1800);
        assertThat((Map<String,Object>)view.get("portfolio")).containsEntry("done",1).containsEntry("blocked",1);
        assertThatThrownBy(()->api.view(owner,UUID.fromString("20000000-0000-0000-0000-000000000002"))).isInstanceOfSatisfying(Problem.class,e->assertThat(e.status).isEqualTo(404));
    }
}

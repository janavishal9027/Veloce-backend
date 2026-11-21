package com.veloce.study;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.veloce.common.*;
import com.veloce.workspace.WorkspaceStore;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="VELOCE_UI_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:5432/veloce_history_verify_261115")
class StudyPreparationPostgresTest {
    @Test void typedDayDetailsAndChartVersionsStayPrivateAndOnlyKeepPersists() throws Exception {
        var source=new DriverManagerDataSource(System.getenv("VELOCE_UI_TEST_DB_URL"),System.getenv("VELOCE_UI_TEST_DB_USER"),
            System.getenv("VELOCE_UI_TEST_DB_PASSWORD"));
        try(var conn=source.getConnection()) {
            conn.setAutoCommit(false);
            var db=new JdbcTemplate(new SingleConnectionDataSource(conn,true));
            try {
                var json=new Json(new ObjectMapper().findAndRegisterModules());
                var clock=Clock.fixed(Instant.parse("2026-11-17T13:00:00Z"),ZoneOffset.UTC);
                UUID owner=UUID.randomUUID(), other=UUID.randomUUID();
                for(UUID id:new UUID[]{owner,other}) db.update("INSERT INTO accounts(id,email,display_name,password_hash) VALUES(?,?,'Guide test','unused')",id,id+"@example.test");
                var ids=StudyCheckInPostgresTest.workspace(db,owner);
                var first=StudyCheckInPostgresTest.slot(db,ids,"2026-11-17","2026-11-17T09:00:00Z",35,"STUDY");
                StudyCheckInPostgresTest.slot(db,ids,"2026-11-17","2026-11-17T09:35:00Z",30,"STUDY");
                db.update("UPDATE roadmap_nodes SET outcome=?,resources=?::jsonb WHERE workspace_id=?",
                    "Concepts\nTransactions; safety\n\nCoding practice\nCreate and roll back a transaction.\n\nInterview prompt\nExplain isolation.",
                    "[{\"title\":\"PostgreSQL documentation\",\"url\":\"https://www.postgresql.org/docs/\"}]",ids[0]);
                var study=new StudyStore(new WorkspaceStore(db,json),clock);
                var preferences=new StudyPreferences(study,"local-test-credential-secret");
                var http=new EnhancementHttp(json,request->{throw new AssertionError("Roadmap mode must not call an AI provider");});
                var ai=new PreparationAi(preferences,json,new FreeAiModels(http,clock),http,clock);
                var api=new StudyPreparationApi(study,ai,new Limits(clock));
                var input=json.object().put("date","2026-11-17").put("mode","ROADMAP");
                var day=api.day(owner,ids[0],"2026-11-17");
                assertThat(day.activities()).hasSize(1);
                assertThat(day.plannedMinutes()).isEqualTo(65);
                var original=api.preview(owner,ids[0],input);
                var extended=api.preview(owner,ids[0],input.deepCopy().put("more",true));
                assertThat(extended.chart().steps()).hasSizeGreaterThan(original.chart().steps().size());
                assertThat(extended.chart().steps().stream().mapToInt(PreparationGuide.Step::minutes).sum()).isEqualTo(65);
                assertThat(db.queryForObject("SELECT count(*) FROM study_day_enhancements WHERE workspace_id=?",Integer.class,ids[0])).isZero();
                assertThatThrownBy(()->api.keep(other,ids[0],extended.id(),input)).isInstanceOfSatisfying(Problem.class,p->assertThat(p.status).isEqualTo(404));
                var saved=api.keep(owner,ids[0],extended.id(),input).kept();
                assertThat(saved.version()).isEqualTo(1);
                assertThat(saved.chart().sources()).hasSize(1);
                var reopened=new StudyPreparationApi(study,ai,new Limits(clock));
                assertThat(reopened.keep(owner,ids[0],extended.id(),input).kept()).isEqualTo(saved);
                assertThatThrownBy(()->api.keep(owner,ids[0],original.id(),input)).isInstanceOfSatisfying(Problem.class,p->assertThat(p.status).isEqualTo(409));
                var newer=api.preview(owner,ids[0],input);
                db.update("UPDATE schedule_slots SET minutes=minutes-1 WHERE id=?",first);
                assertThat(api.day(owner,ids[0],"2026-11-17").keptFromEarlierPlan()).isTrue();
                assertThatThrownBy(()->api.keep(owner,ids[0],newer.id(),input)).isInstanceOfSatisfying(Problem.class,p->assertThat(p.status).isEqualTo(409));
            } finally {
                conn.rollback();
            }
        }
    }
}

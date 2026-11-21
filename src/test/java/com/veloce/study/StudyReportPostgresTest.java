package com.veloce.study;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.veloce.common.*;
import com.veloce.workspace.WorkspaceStore;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="VELOCE_UI_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:5432/veloce_history_verify_261115")
class StudyReportPostgresTest {
    @Test void retriesReturnTheOriginalSnapshotAndPastWorkAppearsInProgressAndHistory() throws Exception {
        var source=new DriverManagerDataSource(System.getenv("VELOCE_UI_TEST_DB_URL"),System.getenv("VELOCE_UI_TEST_DB_USER"),System.getenv("VELOCE_UI_TEST_DB_PASSWORD"));
        try(var connection=source.getConnection()) {
            connection.setAutoCommit(false);var db=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            try {
                var json=new Json(new ObjectMapper().findAndRegisterModules());UUID owner=UUID.randomUUID(),other=UUID.randomUUID();
                for(var id:List.of(owner,other))db.update("INSERT INTO accounts(id,email,display_name,password_hash) VALUES(?,?,?,'unused')",id,id+"@example.test","Report test");
                var f=StudyCheckInPostgresTest.workspace(db,owner);
                StudyCheckInPostgresTest.slot(db,f,"2026-11-17","2026-11-17T12:30:00Z",30,"STUDY");
                var store=new StudyStore(new WorkspaceStore(db,json),Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"),ZoneOffset.UTC));
                var api=new StudyApi(store);var work=json.object().put("date","2026-11-17").put("expectedVersion",0).put("mutationId",UUID.randomUUID().toString());
                work.putObject("data").put("status","DONE").put("actualMinutes",20).put("notes","Verified the concepts and result");
                api.work(owner,f[0],"topic",work);
                var day=api.day(owner,f[0],"2026-11-17");
                var report=json.object().put("date","2026-11-17").put("expectedVersion",0).put("mutationId",UUID.randomUUID().toString())
                    .put("workFingerprint",day.get("workFingerprint").toString()).put("summary","First report");
                assertThat(api.submit(owner,f[0],report).get("version")).isEqualTo(1L);
                assertThat(api.submit(owner,f[0],report).get("version")).isEqualTo(1L);
                work.put("expectedVersion",1).put("mutationId",UUID.randomUUID().toString());((com.fasterxml.jackson.databind.node.ObjectNode)work.path("data")).put("actualMinutes",25);
                api.work(owner,f[0],"topic",work);
                var second=report.deepCopy().put("expectedVersion",1).put("mutationId",UUID.randomUUID().toString()).put("summary","Updated report")
                    .put("workFingerprint",api.day(owner,f[0],"2026-11-17").get("workFingerprint").toString());
                assertThat(api.submit(owner,f[0],second).get("version")).isEqualTo(2L);
                assertThat(api.submit(owner,f[0],report).get("version")).isEqualTo(1L);
                var wrong=report.deepCopy().put("summary","Different submission");
                assertThatThrownBy(()->api.submit(owner,f[0],wrong)).isInstanceOfSatisfying(Problem.class,e->assertThat(e.status).isEqualTo(409));
                assertThatThrownBy(()->api.submit(other,f[0],report)).isInstanceOfSatisfying(Problem.class,e->assertThat(e.status).isEqualTo(404));
                var view=new StudyViewApi(store).view(owner,f[0]);
                assertThat(((Map<?,?>)view.get("totals")).get("reportedMinutes")).isEqualTo(25);
                assertThat(((Map<?,?>)view.get("totals")).get("completeDays")).isEqualTo(1);
                // History uses a server-time cutoff; keep generated DB timestamps inside this fixed test clock.
                db.update("UPDATE study_record_history SET recorded_at='2026-11-20T11:59:00Z' WHERE workspace_id=?",f[0]);
                db.update("UPDATE study_reports SET submitted_at='2026-11-20T11:59:00Z' WHERE workspace_id=?",f[0]);
                var history=new StudyHistoryApi(store).history(owner,f[0],null,null,"ALL",0,null);
                assertThat(history.events()).hasSize(4).allMatch(e->e.date().equals(LocalDate.parse("2026-11-17")));
                assertThat(history.events().stream().filter(e->e.type().equals("REPORT"))).hasSize(2);

                var previewId=UUID.randomUUID();
                var nextPlan=UUID.randomUUID();
                String newSettings="{\"preparation\":{\"startDate\":\"2026-11-20\",\"zone\":\"Asia/Calcutta\"}}";
                db.update("""
                    INSERT INTO plan_previews(id,workspace_id,roadmap_id,workspace_revision,schedule_revision,planner_version,
                        fingerprint,settings,schedule,feasible,reschedule,expires_at)
                    VALUES(?,?,?,0,0,2,repeat('a',64),?::jsonb,'[]',true,true,now()+interval '1 day')
                    """,previewId,f[0],f[1],newSettings);
                db.update("UPDATE plan_versions SET status='SUPERSEDED' WHERE id=?",f[2]);
                db.update("""
                    INSERT INTO plan_versions(id,workspace_id,roadmap_id,preview_id,status,settings,summary)
                    VALUES(?,?,?,?,'ACTIVE',?::jsonb,'{}')
                    """,nextPlan,f[0],f[1],previewId,newSettings);
                db.update("UPDATE workspaces SET active_plan_id=? WHERE id=?",nextPlan,f[0]);
                db.update("UPDATE onboarding_drafts SET data=?::jsonb WHERE workspace_id=?",newSettings,f[0]);
                var after=new StudyViewApi(store).view(owner,f[0]);
                assertThat(((Map<?,?>)after.get("totals")).get("completeDays")).isEqualTo(1);
                assertThat(api.day(owner,f[0],"2026-11-17").get("plannedMinutes")).isEqualTo(30);
                assertThat(new StudyHistoryApi(store).history(owner,f[0],null,null,"ALL",0,null).events()).hasSize(4);
            }finally{connection.rollback();}
        }
    }
}

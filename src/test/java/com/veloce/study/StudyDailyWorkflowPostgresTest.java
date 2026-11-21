package com.veloce.study;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
class StudyDailyWorkflowPostgresTest {
    @Test void dailySectionsDraftsReportsHistoryCarryoverAndLeaveStayWorkspaceScoped() throws Exception {
        var source=new DriverManagerDataSource(System.getenv("VELOCE_UI_TEST_DB_URL"),System.getenv("VELOCE_UI_TEST_DB_USER"),System.getenv("VELOCE_UI_TEST_DB_PASSWORD"));
        try(var connection=source.getConnection()) {
            connection.setAutoCommit(false);var db=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            try {
                var json=new Json(new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));UUID owner=UUID.randomUUID(),stranger=UUID.randomUUID();
                for(var id:List.of(owner,stranger))db.update("INSERT INTO accounts(id,email,display_name,password_hash) VALUES(?,?,?,'unused')",id,id+"@example.test","Daily workflow QA");
                var w=StudyCheckInPostgresTest.workspace(db,owner);var other=StudyCheckInPostgresTest.workspace(db,owner);
                db.update("UPDATE roadmap_nodes SET outcome=? WHERE workspace_id=?",DailySectionsTest.OUTCOME,w[0]);
                StudyCheckInPostgresTest.slot(db,w,"2026-11-20","2026-11-20T12:30:00Z",180,"STUDY");
                StudyCheckInPostgresTest.slot(db,w,"2026-11-19","2026-11-19T12:30:00Z",180,"STUDY");
                var s=new StudyStore(new WorkspaceStore(db,json),Clock.fixed(Instant.parse("2026-11-20T12:45:00Z"),ZoneOffset.UTC));
                var api=new StudyApi(s);var carry=new StudyCarryoverApi(s);
                assertThat((List<?>)api.day(owner,w[0],"2026-11-20").get("reportActivities")).hasSize(6);
                var input=request(json,"2026-11-20",0,"CONCEPT");
                assertThatThrownBy(()->api.work(owner,w[0],"topic",input)).isInstanceOfSatisfying(Problem.class,e->assertThat(e.getMessage()).contains("Check in"));
                var draft=input.deepCopy().put("nodeId","topic").put("targetType","DAILY").put("section","CONCEPT").put("baseVersion",0);
                api.draft(owner,w[0],draft);
                assertThat(s.day(w[0],w[1],LocalDate.parse("2026-11-20"))).isEmpty();
                assertThat(s.get(w[0],w[1],"DRAFT","DAILY/2026-11-20/topic/CONCEPT")).isPresent();
                db.update("INSERT INTO study_presence(id,owner_id,workspace_id,study_date,started_at) VALUES(?,?,?,'2026-11-20','2026-11-20T12:35:00Z')",UUID.randomUUID(),owner,w[0]);
                long version=0;
                for(String part:DailySections.TITLES.keySet())if(!part.equals("REVIEW")) {
                    var request=request(json,"2026-11-20",version,part);request.put("draftVersion",part.equals("CONCEPT")?1:0);
                    var saved=api.work(owner,w[0],"topic",request);version=saved.version();
                    assertThat(api.work(owner,w[0],"topic",request).version()).isEqualTo(version);
                }
                assertThat(s.get(w[0],w[1],"WORK","2026-11-20/topic").orElseThrow().data().path("actualMinutes").asInt()).isEqualTo(100);
                assertThat(api.history(owner,w[0],"DAILY","2026-11-20/topic/CONCEPT",0)).hasSize(1);
                assertThatThrownBy(()->api.work(owner,w[0],"topic",request(json,"2026-11-20",0,"CONCEPT"))).isInstanceOf(Problem.class);
                var day=api.day(owner,w[0],"2026-11-20");assertThat((List<?>)day.get("reportIssues")).isEmpty();
                var report=json.object().put("date","2026-11-20").put("expectedVersion",0).put("mutationId",UUID.randomUUID().toString())
                    .put("workFingerprint",day.get("workFingerprint").toString()).put("summary","Completed all five work domains");
                assertThat(api.submit(owner,w[0],report).get("version")).isEqualTo(1L);
                var totals=(Map<?,?>)new StudyViewApi(s).view(owner,w[0]).get("totals");
                assertThat(totals.get("completeDays")).isEqualTo(1);assertThat(totals.get("reportedMinutes")).isEqualTo(100);
                db.update("UPDATE study_record_history SET recorded_at='2026-11-20T12:44:00Z' WHERE workspace_id=?",w[0]);
                db.update("UPDATE study_reports SET submitted_at='2026-11-20T12:44:00Z' WHERE workspace_id=?",w[0]);
                assertThat(new StudyHistoryApi(s).history(owner,w[0],null,null,"ALL",0,null).events()).hasSize(6);
                var backup=json.object().put("schema","veloce-study-backup-v1").put("accountId",owner.toString()).put("workspaceId",w[0].toString()).put("roadmapId",w[1].toString());
                backup.set("records",json.mapper.valueToTree(s.day(w[0],w[1],LocalDate.parse("2026-11-20"))));
                assertThat(((Map<?,?>)new StudyTransferApi(s).restore(owner,w[0],backup)).get("restoredDrafts")).isEqualTo(5);
                assertThat(((Map<?,?>)new StudyTransferApi(s).restore(owner,w[0],backup)).get("existingDraftsKept")).isEqualTo(5);
                var future=request(json,"2026-11-21",0,"CONCEPT");
                assertThatThrownBy(()->api.work(owner,w[0],"topic",future)).isInstanceOf(Problem.class);
                var past=request(json,"2026-11-19",0,"CONCEPT");
                assertThatThrownBy(()->api.work(owner,w[0],"topic",past)).isInstanceOf(Problem.class);
                ((ObjectNode)past.path("data")).put("correctionReason","Forgot to record yesterday's work");api.work(owner,w[0],"topic",past);
                var bulk=json.object().put("sourceDate","2026-11-19").put("targetDate","2026-11-21").put("cause","TASK_DELAY");
                assertThat(carry.unfinished(owner,w[0],bulk).get("carried")).isEqualTo(5);
                assertThat(carry.unfinished(owner,w[0],bulk).get("carried")).isEqualTo(0);
                assertThat(((Map<?,?>)carry.workspace(owner,w[0],"2026-11-19",0).get("plan")).get("estimatedUnfinishedMinutes")).isEqualTo(0);
                bulk.put("targetDate","2026-11-22");assertThat(carry.unfinished(owner,w[0],bulk).get("carried")).isEqualTo(5);
                assertThat(s.list(w[0],w[1],"CARRY",null,0)).hasSize(5);
                assertThat(s.list(other[0],other[1],"CARRY",null,0)).isEmpty();
                assertThatThrownBy(()->carry.workspace(stranger,w[0],"2026-11-20",0)).isInstanceOfSatisfying(Problem.class,e->assertThat(e.status).isEqualTo(404));
                var leave=json.object().put("expectedVersion",0).put("mutationId",UUID.randomUUID().toString());
                leave.putObject("data").put("date","2026-11-20").put("from","18:30").put("to","19:00").put("reason","Family appointment during study").put("status","PLANNED");
                var leaveId=UUID.randomUUID();carry.leave(owner,w[0],leaveId,leave);
                var plan=(Map<?,?>)carry.workspace(owner,w[0],"2026-11-20",0).get("plan");
                assertThat(plan.get("leaveFocusMinutes")).isEqualTo(30);assertThat(plan.get("availableFocusMinutes")).isEqualTo(145);
                assertThatThrownBy(()->carry.leave(owner,w[0],UUID.randomUUID(),leave)).isInstanceOf(Problem.class);
                leave.put("expectedVersion",1).put("mutationId",UUID.randomUUID().toString());((ObjectNode)leave.path("data")).put("status","CANCELLED");
                carry.leave(owner,w[0],leaveId,leave);
                assertThat(((Map<?,?>)carry.workspace(owner,w[0],"2026-11-20",0).get("plan")).get("leaveFocusMinutes")).isEqualTo(0);
                assertThat(db.queryForObject("SELECT count(*) FROM schedule_slots WHERE workspace_id=? AND cancelled",Integer.class,w[0])).isZero();
            } finally {connection.rollback();}
        }
    }
    private ObjectNode request(Json json,String date,long version,String section) {
        var request=json.object().put("date",date).put("expectedVersion",version).put("mutationId",UUID.randomUUID().toString()).put("dailySection",section);
        request.putObject("data").put("status","DONE").put("actualMinutes",20).put("notes","Saved work for "+section).put("evidence","Verified output")
            .put("validation","3 tests pass").put("confidence",4).put("complexity","O(n)").put("edgeCases","Empty input").put("repository","commit abc");
        return request;
    }
}

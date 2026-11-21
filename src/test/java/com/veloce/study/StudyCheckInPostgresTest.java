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
class StudyCheckInPostgresTest {
    @Test void checkInPersistsLocalDayScheduleAndIsIdempotentAndAccountScoped() throws Exception {
        var source=new DriverManagerDataSource(System.getenv("VELOCE_UI_TEST_DB_URL"),
            System.getenv("VELOCE_UI_TEST_DB_USER"),System.getenv("VELOCE_UI_TEST_DB_PASSWORD"));
        try(var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            var db=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            try {
                var json=new Json(new ObjectMapper());
                var owner=UUID.randomUUID(); var stranger=UUID.randomUUID();
                for(var id:List.of(owner,stranger)) db.update("INSERT INTO accounts(id,email,display_name,password_hash) VALUES(?,?,?,'unused')",id,id+"@example.test","Check in test");
                var first=workspace(db,owner); var other=workspace(db,owner); var privateWorkspace=workspace(db,stranger);
                // Early completion frees its original time without rewriting that completed slot.
                var completed=slot(db,first,"2026-11-17", "2026-11-16T20:45:00Z",15,"STUDY");
                db.update("INSERT INTO session_completions(session_id,workspace_id) VALUES(?,?)",completed,first[0]);
                var touched=slot(db,first,"2026-11-17","2026-11-16T18:45:00Z",15,"STUDY");
                db.update("INSERT INTO focus_sessions(id,owner_id,workspace_id,session_id,started_at,ended_at) VALUES(?,?,?,?,'2026-11-16T18:45:00Z','2026-11-16T18:46:00Z')",UUID.randomUUID(),owner,first[0],touched);
                var lesson=slot(db,first,"2026-11-17","2026-11-16T19:00:00Z",30,"STUDY");
                var rest=slot(db,first,"2026-11-17","2026-11-16T19:30:00Z",10,"BREAK");
                var second=slot(db,first,"2026-11-17","2026-11-16T19:40:00Z",30,"STUDY");
                var obsoleteBreak=slot(db,first,"2026-11-17","2026-11-16T20:10:00Z",5,"BREAK");
                var tomorrow=slot(db,first,"2026-11-18","2026-11-17T19:00:00Z",30,"STUDY");
                var busy=slot(db,other,"2026-11-17","2026-11-16T20:15:00Z",30,"STUDY");
                // Another account never blocks this account's day.
                slot(db,privateWorkspace,"2026-11-17","2026-11-16T20:00:00Z",120,"STUDY");
                var now=Instant.parse("2026-11-16T20:00:12Z"); // 17 November in the workspace timezone.
                var store=new StudyStore(new WorkspaceStore(db,json),Clock.fixed(now,ZoneOffset.UTC));
                var api=new StudyPresenceApi(store,null,null,null,new StudyLifecycle(store),new StudyDaySchedule(store));
                var before=times(db,List.of(completed,touched,tomorrow,busy));
                api.action(owner,first[0],"start",null);
                assertThat(start(db,lesson)).isEqualTo(Instant.parse("2026-11-16T20:45:00Z"));
                assertThat(start(db,rest)).isEqualTo(Instant.parse("2026-11-16T21:15:00Z"));
                assertThat(start(db,second)).isEqualTo(Instant.parse("2026-11-16T21:25:00Z"));
                assertThat(db.queryForObject("SELECT cancelled FROM schedule_slots WHERE id=?",Boolean.class,obsoleteBreak)).isTrue();
                assertThat(times(db,List.of(completed,touched,tomorrow,busy))).isEqualTo(before);
                assertThat(db.queryForObject("SELECT started_at FROM study_presence WHERE workspace_id=?",OffsetDateTime.class,first[0]).toInstant()).isEqualTo(now);
                assertThat(db.queryForObject("SELECT study_date FROM study_presence WHERE workspace_id=?",LocalDate.class,first[0])).isEqualTo(LocalDate.parse("2026-11-17"));
                assertThat(db.queryForObject("SELECT schedule_revision FROM accounts WHERE id=?",Long.class,owner)).isEqualTo(1);
                assertThat(db.queryForObject("SELECT count(*) FROM outbox_events WHERE workspace_id=? AND kind='study-check-in'",Integer.class,first[0])).isEqualTo(1);
                api.action(owner,first[0],"start",null);
                assertThat(db.queryForObject("SELECT count(*) FROM study_presence WHERE workspace_id=?",Integer.class,first[0])).isEqualTo(1);
                assertThat(db.queryForObject("SELECT schedule_revision FROM accounts WHERE id=?",Long.class,owner)).isEqualTo(1);
                assertThatThrownBy(()->api.action(owner,other[0],"start",null)).isInstanceOfSatisfying(Problem.class,e->assertThat(e.status).isEqualTo(409));
                assertThatThrownBy(()->api.action(stranger,first[0],"start",null)).isInstanceOfSatisfying(Problem.class,e->assertThat(e.status).isEqualTo(404));
                var day=new StudyApi(store).day(owner,first[0],"2026-11-17");
                assertThat(day.get("plannedMinutes")).isEqualTo(90);
                assertThat(day.get("completedMinutes")).isEqualTo(15);
                assertThat(day.get("focusSeconds")).isEqualTo(60L); // Check in did not manufacture focus.
                db.update("UPDATE study_presence SET study_date=study_date-1 WHERE workspace_id=?",first[0]);
                assertThatThrownBy(()->api.action(owner,first[0],"start",null)).isInstanceOfSatisfying(Problem.class,e->{
                    assertThat(e.status).isEqualTo(409);assertThat(e.getMessage()).contains("previous study day");
                });
            } finally { connection.rollback(); }
        }
    }

    private static List<Instant> times(JdbcTemplate db,List<UUID> slots) { return slots.stream().map(id->start(db,id)).toList(); }
    private static Instant start(JdbcTemplate db,UUID id) { return db.queryForObject("SELECT starts_at FROM schedule_slots WHERE id=?",OffsetDateTime.class,id).toInstant(); }
    static UUID[] workspace(JdbcTemplate db,UUID owner) {
        UUID w=UUID.randomUUID(),roadmap=UUID.randomUUID(),preview=UUID.randomUUID(),plan=UUID.randomUUID();
        String settings="{\"preparation\":{\"zone\":\"Asia/Calcutta\",\"startDate\":\"2026-11-17\"}}";
        db.update("INSERT INTO workspaces(id,owner_id,name,status) VALUES(?,?,?,'ACTIVE')",w,owner,"Check in test");
        db.update("INSERT INTO onboarding_drafts(workspace_id,step,data) VALUES(?,6,?::jsonb)",w,settings);
        db.update("INSERT INTO roadmap_versions(id,workspace_id,title,industry,target_role) VALUES(?,?,'Roadmap','Technology','Developer')",roadmap,w);
        db.update("INSERT INTO roadmap_nodes(workspace_id,roadmap_id,node_id,ordinal,kind,priority,title,estimated_minutes,outcome,resources) VALUES(?,?,'topic',0,'LESSON','MUST','Study topic',120,'Validate work','[]')",w,roadmap);
        db.update("""
            INSERT INTO plan_previews(id,workspace_id,roadmap_id,workspace_revision,schedule_revision,planner_version,fingerprint,settings,schedule,feasible,reschedule,expires_at)
            VALUES(?,?,?,0,0,1,repeat('a',64),?::jsonb,'[]',true,false,now()+interval '1 day')
            """,preview,w,roadmap,settings);
        db.update("INSERT INTO plan_versions(id,workspace_id,roadmap_id,preview_id,status,settings,summary) VALUES(?,?,?,?,'ACTIVE',?::jsonb,'{}')",plan,w,roadmap,preview,settings);
        db.update("UPDATE workspaces SET active_plan_id=? WHERE id=?",plan,w);
        return new UUID[]{w,roadmap,plan};
    }
    static UUID slot(JdbcTemplate db,UUID[] w,String date,String start,int minutes,String kind) {
        var id=UUID.randomUUID();var at=Instant.parse(start);
        db.update("""
            INSERT INTO schedule_slots(id,workspace_id,plan_id,roadmap_id,node_id,study_date,starts_at,ends_at,minutes,kind)
            VALUES(?,?,?,?,?,?::date,?,?,?,?)
            """,id,w[0],w[2],w[1],kind.equals("BREAK")?null:"topic",date,at.atOffset(ZoneOffset.UTC),at.plusSeconds(minutes*60L).atOffset(ZoneOffset.UTC),minutes,kind);
        return id;
    }
}

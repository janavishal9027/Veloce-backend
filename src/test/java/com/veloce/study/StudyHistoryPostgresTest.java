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
class StudyHistoryPostgresTest {
    @Test void historyPreservesRevisionsAndOldRoadmapsWithBoundedPrivateStablePages() throws Exception {
        var source=new DriverManagerDataSource(System.getenv("VELOCE_UI_TEST_DB_URL"),
            System.getenv("VELOCE_UI_TEST_DB_USER"),System.getenv("VELOCE_UI_TEST_DB_PASSWORD"));
        try (var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            var db=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            try {
                var f=new Fixture(db);
                var api=f.api("2026-11-15T12:00:00Z");
                f.work(f.roadmap,"2026-11-09/topic","2026-11-09",1,"Outside preparation",5,"2026-11-11T10:00:00Z");
                f.work(f.roadmap,"2026-11-10/topic","2026-11-10",1,"Before local start",5,"2026-11-09T18:00:00Z");
                f.work(f.roadmap,"2026-11-10/topic","2026-11-10",2,"After local start",10,"2026-11-09T19:00:00Z");
                f.work(f.roadmap,"2026-11-14/topic","2026-11-14",1,"First solution",20,"2026-11-14T10:00:00Z");
                f.work(f.roadmap,"2026-11-14/topic","2026-11-14",2,"Revised solution",35,"2026-11-15T06:00:00Z");
                f.work(f.roadmap,"2026-11-16/topic","2026-11-16",1,"Future study",0,"2026-11-15T07:00:00Z");
                f.save(f.oldRoadmap,"PROJECT","topic/DESIGN","topic","2026-11-11",1,
                    "{\"status\":\"DONE\",\"actualMinutes\":45,\"notes\":\"Original project design\"}","2026-11-11T20:00:00Z");
                db.update("""
                    INSERT INTO study_reports(workspace_id,roadmap_id,study_date,version,mutation_id,work_fingerprint,data,submitted_at)
                    VALUES(?,?,'2026-11-14',1,?,repeat('a',64),'{"summary":"Daily evidence","entries":[]}'::jsonb,'2026-11-14T23:00:00Z')
                    """,f.workspace,f.roadmap,UUID.randomUUID());
                for (int i=0;i<35;i++) {
                    f.node(f.roadmap,"batch"+i,i+1);
                    f.save(f.roadmap,"WORK","2026-11-12/batch"+i,"batch"+i,"2026-11-12",1,
                        "{\"status\":\"DONE\",\"actualMinutes\":1}",Instant.parse("2026-11-12T10:00:00Z").plusSeconds(i).toString());
                }
                var first=api.history(f.owner,f.workspace,"2000-01-01","2200-12-31","ALL",0,null);
                assertThat(first.startDate()).isEqualTo(LocalDate.parse("2026-11-10"));
                assertThat(first.today()).isEqualTo(LocalDate.parse("2026-11-15"));
                assertThat(first.events()).hasSize(30);
                assertThat(first.hasMore()).isTrue();

                // New saves between pages must not move the first page's snapshot.
                f.node(f.roadmap,"late",40);
                f.save(f.roadmap,"WORK","2026-11-15/late","late","2026-11-15",1,
                    "{\"status\":\"DONE\",\"actualMinutes\":1}","2026-11-15T12:30:00Z");
                var newer=f.api("2026-11-15T13:00:00Z");
                var second=newer.history(f.owner,f.workspace,null,null,"ALL",1,first.asOf());
                var all=new ArrayList<>(first.events()); all.addAll(second.events());
                assertThat(all).hasSize(40).doesNotHaveDuplicates();
                assertThat(second.hasMore()).isFalse();
                assertThat(all).anyMatch(e->e.roadmapId().equals(f.oldRoadmap))
                    .anyMatch(e->e.type().equals("REPORT"));
                assertThat(all).allMatch(e->!e.date().isBefore(first.startDate())&&!e.date().isAfter(first.today()));
                assertThat(newer.history(f.owner,f.workspace,null,null,"ALL",0,null).events().getFirst().key()).isEqualTo("2026-11-15/late");

                var revision=api.update(f.owner,f.workspace,f.roadmap,"WORK","2026-11-14/topic",2);
                assertThat(revision.data().path("notes").asText()).isEqualTo("Revised solution");
                assertThat(revision.previous().path("notes").asText()).isEqualTo("First solution");
                assertThat(revision.changedFields()).containsExactly("actualMinutes","notes");
                assertThat(api.update(f.owner,f.workspace,f.oldRoadmap,"PROJECT","topic/DESIGN",1).event().section()).isEqualTo("DESIGN");
                assertThat(api.history(f.owner,f.workspace,null,null,"PROJECT",0,null).events()).hasSize(1);

                assertThatThrownBy(()->api.history(f.other,f.workspace,null,null,"ALL",0,null))
                    .isInstanceOfSatisfying(Problem.class,e->assertThat(e.status).isEqualTo(404));
                assertThatThrownBy(()->api.update(f.other,f.workspace,f.roadmap,"WORK","2026-11-14/topic",2))
                    .isInstanceOfSatisfying(Problem.class,e->assertThat(e.status).isEqualTo(404));
                for (var pair:List.of(List.of("2026-11-09/topic",1L),List.of("2026-11-10/topic",1L),List.of("2026-11-16/topic",1L))) {
                    assertThatThrownBy(()->api.update(f.owner,f.workspace,f.roadmap,"WORK",(String)pair.getFirst(),(Long)pair.get(1)))
                        .isInstanceOfSatisfying(Problem.class,e->assertThat(e.status).isEqualTo(404));
                }
                db.update("UPDATE onboarding_drafts SET data=jsonb_set(data,'{preparation,startDate}','\"2026-11-20\"') WHERE workspace_id=?",f.workspace);
                assertThat(api.history(f.owner,f.workspace,null,null,"ALL",0,null).events()).isEmpty();
            } finally { connection.rollback(); }
        }
    }
    private static final class Fixture {
        final JdbcTemplate db; final Json json=new Json(new ObjectMapper());
        final UUID owner=UUID.randomUUID(),other=UUID.randomUUID(),workspace=UUID.randomUUID(),
            roadmap=UUID.randomUUID(),oldRoadmap=UUID.randomUUID();
        Fixture(JdbcTemplate db) {
            this.db=db;
            db.update("INSERT INTO accounts(id,email,display_name,password_hash) VALUES(?,?,?,?)",owner,owner+"@example.test","History test","not-a-password");
            db.update("INSERT INTO accounts(id,email,display_name,password_hash) VALUES(?,?,?,?)",other,other+"@example.test","Other owner","not-a-password");
            db.update("INSERT INTO workspaces(id,owner_id,name,status) VALUES(?,?,?,'ARCHIVED')",workspace,owner,"History test");
            db.update("INSERT INTO onboarding_drafts(workspace_id,step,data) VALUES(?,6,'{\"preparation\":{\"startDate\":\"2026-11-10\",\"zone\":\"Asia/Calcutta\"}}'::jsonb)",workspace);
            for (var id:List.of(roadmap,oldRoadmap)) {
                db.update("INSERT INTO roadmap_versions(id,workspace_id,title,industry,target_role) VALUES(?,?,'History roadmap','Technology','Developer')",id,workspace);
                node(id,"topic",0);
            }
        }
        void node(UUID roadmap,String id,int ordinal) {
            db.update("""
                INSERT INTO roadmap_nodes(workspace_id,roadmap_id,node_id,ordinal,kind,priority,title,estimated_minutes,outcome,resources)
                VALUES(?,?,?,?,'LESSON','MUST',?,30,'Review the result','[]')
                """,workspace,roadmap,id,ordinal,"History "+id);
        }
        void work(UUID roadmap,String key,String date,long version,String notes,int minutes,String recorded) {
            save(roadmap,"WORK",key,"topic",date,version,json.write(json.object().put("status","DONE").put("notes",notes).put("actualMinutes",minutes)),recorded);
        }
        void save(UUID roadmap,String type,String key,String node,String date,long version,String data,String recorded) {
            db.update("""
                INSERT INTO study_records(workspace_id,roadmap_id,record_type,record_key,node_id,study_date,version,data)
                VALUES(?,?,?,?,?,?::date,?,?::jsonb)
                ON CONFLICT(workspace_id,roadmap_id,record_type,record_key) DO UPDATE SET version=excluded.version,data=excluded.data
                """,workspace,roadmap,type,key,node,date,version,data);
            db.update("""
                INSERT INTO study_record_history(workspace_id,roadmap_id,record_type,record_key,node_id,study_date,version,mutation_id,data,recorded_at)
                VALUES(?,?,?,?,?,?::date,?,?,?::jsonb,?::timestamptz)
                """,workspace,roadmap,type,key,node,date,version,UUID.randomUUID(),data,recorded);
        }
        StudyHistoryApi api(String instant) {
            return new StudyHistoryApi(new StudyStore(new WorkspaceStore(db,json),Clock.fixed(Instant.parse(instant),ZoneOffset.UTC)));
        }
    }
}


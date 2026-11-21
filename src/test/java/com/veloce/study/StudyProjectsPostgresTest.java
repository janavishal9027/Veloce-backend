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
class StudyProjectsPostgresTest {
    @Test void countsUseAllProjectsAndOnlyCurrentNoncancelledLocalDaySessions() throws Exception {
        var source=new DriverManagerDataSource(System.getenv("VELOCE_UI_TEST_DB_URL"),
            System.getenv("VELOCE_UI_TEST_DB_USER"),System.getenv("VELOCE_UI_TEST_DB_PASSWORD"));
        try(var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            var db=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            try {
                var json=new Json(new ObjectMapper());
                UUID owner=UUID.randomUUID(),other=UUID.randomUUID(),w=UUID.randomUUID(),r=UUID.randomUUID(),
                    active=UUID.randomUUID(),old=UUID.randomUUID();
                db.update("INSERT INTO accounts(id,email,display_name,password_hash) VALUES(?,?,?,'not-a-password')",owner,owner+"@example.test","Projects test");
                db.update("INSERT INTO accounts(id,email,display_name,password_hash) VALUES(?,?,?,'not-a-password')",other,other+"@example.test","Other owner");
                db.update("INSERT INTO workspaces(id,owner_id,name,status) VALUES(?,?,?,'ACTIVE')",w,owner,"Project counts");
                var settings="{\"preparation\":{\"zone\":\"Asia/Calcutta\",\"startDate\":\"2026-11-10\"}}";
                db.update("INSERT INTO onboarding_drafts(workspace_id,step,data) VALUES(?,6,?::jsonb)",w,settings);
                db.update("INSERT INTO roadmap_versions(id,workspace_id,title,industry,target_role) VALUES(?,?,'Projects','Technology','Developer')",r,w);
                for(int i=1;i<=5;i++) db.update("""
                    INSERT INTO roadmap_nodes(workspace_id,roadmap_id,node_id,ordinal,kind,priority,title,estimated_minutes,outcome,resources)
                    VALUES(?,?,?,?,?,'MUST',?,120,'Validate the deliverable','[]')
                    """,w,r,"p"+i,i,i==5?"LESSON":"PROJECT","Project "+i);
                for(var plan:List.of(old,active)) {
                    var preview=UUID.randomUUID();
                    db.update("""
                        INSERT INTO plan_previews(id,workspace_id,roadmap_id,workspace_revision,schedule_revision,planner_version,fingerprint,settings,schedule,feasible,reschedule,expires_at)
                        VALUES(?,?,?,0,0,1,repeat('a',64),?::jsonb,'[]',true,false,now()+interval '1 day')
                        """,preview,w,r,settings);
                    db.update("INSERT INTO plan_versions(id,workspace_id,roadmap_id,preview_id,status,settings,summary) VALUES(?,?,?,?,?,?::jsonb,'{}')",
                        plan,w,r,preview,plan.equals(active)?"ACTIVE":"SUPERSEDED",settings);
                }
                db.update("UPDATE workspaces SET active_plan_id=? WHERE id=?",active,w);
                for(var section:StudyRules.SECTIONS) save(db,json,w,r,"p1",section,"DONE");
                for(var section:StudyRules.SECTIONS) if(!section.equals("REVIEW")) save(db,json,w,r,"p2",section,"DONE");
                save(db,json,w,r,"p3","DESIGN","BLOCKED");
                int index=0;
                slot(db,w,r,active,"p1","2026-11-15",false,index++);
                slot(db,w,r,active,"p1","2026-11-15",false,index++);
                slot(db,w,r,active,"p2","2026-11-15",false,index++);
                slot(db,w,r,active,"p2","2026-11-15",false,index++);
                slot(db,w,r,active,"p3","2026-11-15",true,index++);
                slot(db,w,r,old,"p3","2026-11-15",false,index++);
                slot(db,w,r,active,"p4","2026-11-16",false,index++);
                slot(db,w,r,active,"p5","2026-11-15",false,index);
                // UTC is still 14 November; the workspace has already entered 15 November.
                var api=new StudyProjectsApi(new StudyStore(new WorkspaceStore(db,json),
                    Clock.fixed(Instant.parse("2026-11-14T20:00:00Z"),ZoneOffset.UTC)));
                var all=api.catalog(owner,w,"","ALL",0);
                assertThat(all.overview()).isEqualTo(new StudyProjectsApi.Overview(4,1,3,2,1,LocalDate.parse("2026-11-15"),"Asia/Calcutta"));
                assertThat(all.projects()).hasSize(4);
                assertThat(all.projects().getFirst().completedSections()).isEqualTo(6);
                assertThat(api.catalog(owner,w,"","TODAY",0).projects()).extracting(ProjectCatalog.Project::id).containsExactly("p2");
                var searched=api.catalog(owner,w,"P02","PENDING",0);
                assertThat(searched.projects()).extracting(ProjectCatalog.Project::number).containsExactly(2);
                assertThat(searched.overview()).isEqualTo(all.overview());
                assertThat(api.catalog(owner,w,"no match","ALL",0).matching()).isZero();
                assertThat(api.project(owner,w,"p3").status()).isEqualTo("BLOCKED");
                assertThatThrownBy(()->api.catalog(other,w,"","ALL",0))
                    .isInstanceOfSatisfying(Problem.class,e->assertThat(e.status).isEqualTo(404));
                assertThatThrownBy(()->api.project(other,w,"p1"))
                    .isInstanceOfSatisfying(Problem.class,e->assertThat(e.status).isEqualTo(404));
                assertThatThrownBy(()->api.project(owner,w,"p5"))
                    .isInstanceOfSatisfying(Problem.class,e->assertThat(e.status).isEqualTo(404));
                save(db,json,w,r,"p2","REVIEW","DONE");
                var refreshed=api.catalog(owner,w,"","ALL",0).overview();
                assertThat(refreshed.completed()).isEqualTo(2);
                assertThat(refreshed.pending()).isEqualTo(2);
                assertThat(refreshed.todayPending()).isZero();
                assertThat(refreshed.todayTotal()).isEqualTo(2);
                // Supplementary backlog projects are available without adding full-build hours to the timetable.
                var bankSource=UUID.randomUUID();
                var analysis=json.mapper.readTree("""
                    {"sheets":[{"id":"projects","name":"PROJECT BACKLOG","included":false,
                      "headers":["ID","REAL-WORLD PROJECT","LEVEL","TECH STACK","SCOPE / PROTOTYPE","FULL BUILD EST. HRS"],
                      "mapping":{"id":0,"title":1,"estimatedMinutes":5},
                      "rows":[{"cells":["P01","Project 1","Beginner","Python","Build a project","2"]},
                               {"cells":["P02","Backlog converter","Intermediate","Python, decimal","Convert units","4"]}]}]}
                    """);
                db.update("INSERT INTO roadmap_sources(id,workspace_id,filename,format,sha256,content,state,profile,analysis) VALUES(?,?,'projects.xlsx','xlsx',repeat('b',64),?,'READY','{}',?::jsonb)",
                    bankSource,w,new byte[0],json.write(analysis));
                db.update("UPDATE roadmap_versions SET source_id=? WHERE workspace_id=? AND id=?",bankSource,w,r);
                db.update("UPDATE roadmap_nodes SET outcome='Project workstream: P02 · Backlog converter' WHERE workspace_id=? AND roadmap_id=? AND node_id='p5'",w,r);
                var expanded=api.catalog(owner,w,"","ALL",0);
                assertThat(expanded.overview().total()).isEqualTo(5); // P01 deduplicates against the imported project.
                assertThat(expanded.overview().todayPending()).isEqualTo(1);
                var backlog=expanded.projects().stream().filter(ProjectCatalog.Project::supplemental).findFirst().orElseThrow();
                assertThat(backlog.externalId()).isEqualTo("P02");
                assertThat(backlog.stack()).isEqualTo("Python, decimal");
                assertThat(backlog.minutes()).isEqualTo(240);
                assertThat(backlog.scheduledToday()).isTrue();
                var store=new StudyStore(new WorkspaceStore(db,json),Clock.fixed(Instant.parse("2026-11-14T20:00:00Z"),ZoneOffset.UTC));
                var workApi=new StudyApi(store);
                var work=json.object().put("status","DONE").put("actualMinutes",10).put("notes","Validated conversion behavior").put("evidence","Tests and sample output");
                var draft=workApi.draft(owner,w,json.object().put("nodeId",backlog.id()).put("targetType","PROJECT")
                    .put("section","DESIGN").put("date","2026-11-15").put("expectedVersion",0).put("baseVersion",0)
                    .put("mutationId",UUID.randomUUID().toString()).set("data",work));
                assertThat(draft.nodeId()).isNull();
                for(var section:StudyRules.SECTIONS) {
                    var input=json.object().put("expectedVersion",0).put("mutationId",UUID.randomUUID().toString());
                    if(section.equals("DESIGN")) input.put("draftVersion",draft.version());
                    input.set("data",work);
                    assertThat(workApi.project(owner,w,backlog.id(),section,input).nodeId()).isNull();
                }
                assertThat(api.project(owner,w,backlog.id()).completedSections()).isEqualTo(6);
                assertThat(api.catalog(owner,w,"","ALL",0).overview().completed()).isEqualTo(3);
                assertThat(api.catalog(owner,w,"","ALL",0).overview().todayPending()).isZero();
                db.update("UPDATE study_record_history SET recorded_at='2026-11-14T19:00:00Z' WHERE workspace_id=? AND record_type='PROJECT'",w);
                var updates=new StudyHistoryApi(store).history(owner,w,null,null,"PROJECT",0,null);
                assertThat(updates.events()).hasSize(6).allMatch(e->e.title().equals("Backlog converter"));
                var presentation=new StudyViewApi(store).view(owner,w);
                assertThat(((Map<?,?>)presentation.get("totals")).get("projectCount")).isEqualTo(5);
                assertThat(((Map<?,?>)presentation.get("totals")).get("plannedMinutes")).isEqualTo(90);
                var record=json.object().put("type","PROJECT").put("key",backlog.id()+"/DESIGN").putNull("nodeId")
                    .put("date","2026-11-15").put("version",1);
                record.set("data",work);
                var backup=json.object().put("schema","veloce-study-backup-v1").put("accountId",owner.toString())
                    .put("workspaceId",w.toString()).put("roadmapId",r.toString());
                backup.set("records",json.mapper.createArrayNode().add(record));
                assertThat(((Map<?,?>)new StudyTransferApi(store).restore(owner,w,backup)).get("restoredDrafts")).isEqualTo(1);
            } finally { connection.rollback(); }
        }
    }
    private static void save(JdbcTemplate db,Json json,UUID w,UUID r,String node,String section,String status) {
        var data=json.object().put("status",status).put("actualMinutes",5).put("notes","Validated this section").put("evidence","Local test evidence");
        db.update("""
            INSERT INTO study_records(workspace_id,roadmap_id,record_type,record_key,node_id,study_date,version,data)
            VALUES(?,?,'PROJECT',?,?,'2026-11-15',1,?::jsonb)
            """,w,r,node+"/"+section,node,json.write(data));
    }
    private static void slot(JdbcTemplate db,UUID w,UUID r,UUID plan,String node,String day,boolean cancelled,int index) {
        var start=LocalDate.parse(day).atTime(3,0).atOffset(ZoneOffset.UTC).plusMinutes(index*15L);
        db.update("""
            INSERT INTO schedule_slots(id,workspace_id,plan_id,roadmap_id,node_id,study_date,starts_at,ends_at,minutes,kind,cancelled)
            VALUES(?,?,?,?,?,?::date,?,?,15,'STUDY',?)
            """,UUID.randomUUID(),w,plan,r,node,day,start,start.plusMinutes(15),cancelled);
    }
}


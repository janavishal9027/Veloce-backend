package com.veloce.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloce.common.*;
import com.veloce.integration.ReminderRules;
import com.veloce.roadmap.Roadmap.Document;
import com.veloce.roadmap.RoadmapStore;
import com.veloce.workspace.WorkspaceStore;
import com.veloce.workspace.WorkspaceStore.Workspace;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class PlanService {
    record Snapshot(UUID id,UUID workspace,UUID roadmap,long revision,long accountRevision,int plannerVersion,
                    String fingerprint,JsonNode settings,Planner.Preview plan,boolean reschedule,Instant expiresAt) {}
    record Job(UUID id,UUID previewId,String state,String stage,int attempts,UUID planId,String error) {}
    record PreviewView(UUID id,long revision,int plannerVersion,String fingerprint,Instant expiresAt,boolean reschedule,
                       JsonNode settings,ObjectNode summary,List<Map<String,Object>> slots) {}
    private final WorkspaceStore workspaces;
    private final RoadmapStore roadmaps;
    private final Json json;
    private final JdbcTemplate db;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    private final com.veloce.study.StudyLifecycle studyLifecycle;
    PlanService(WorkspaceStore ws,RoadmapStore roadmaps,Clock clock,TransactionTemplate transaction,
                @Value("${veloce.jobs-enabled}") boolean enabled,com.veloce.study.StudyLifecycle studyLifecycle) {
        this.workspaces=ws; this.roadmaps=roadmaps; this.json=ws.json; this.db=ws.db;
        this.clock=clock; this.transaction=transaction; this.enabled=enabled; this.studyLifecycle=studyLifecycle;
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    PreviewView preview(UUID owner,UUID id,JsonNode input,boolean reschedule) {
        long accountRevision=workspaces.accountRevision(owner,false);
        var w=workspaces.owned(owner,id);
        workspaces.expect(w,Json.revision(input));
        UUID roadmap=w.roadmapId();
        JsonNode settings=w.data();
        if (reschedule) {
            if (!Set.of("ACTIVE","PAUSED").contains(w.status())) throw Problem.conflict("Only an active or paused plan can be rebalanced.");
            roadmap=db.queryForObject("SELECT roadmap_id FROM plan_versions WHERE id=?",UUID.class,w.activePlanId());
            if (!input.path("preparation").isObject() || !input.path("timetable").isObject()) throw Problem.invalid("Enter your revised preparation settings.");
            var merged=(ObjectNode)w.data().deepCopy();
            Planner.Settings.checkDailyHoursChoice(input.path("preparation"), merged.path("preparation"));
            merged.set("preparation",input.path("preparation")); merged.set("timetable",input.path("timetable"));
            settings=merged;
        } else {
            workspaces.draft(w);
            if (w.step()<5 || roadmap==null || !settings.path("timetable").isObject())
                throw Problem.invalid("Confirm your roadmap and save the timetable first.");
        }
        if (json.write(settings).length()>70_000) throw Problem.invalid("Your settings are too large.");
        var config=Planner.Settings.from(settings,clock,reschedule);
        ReminderRules.from(settings.path("timetable").path("reminders"),json);
        var doc=roadmaps.get(id,roadmap);
        Instant earliest=clock.instant().plusSeconds(180).truncatedTo(ChronoUnit.MINUTES);
        var finished=reschedule?completed(id,roadmap):Map.<String,Integer>of();
        var busy=commitments(owner,id,config,earliest,reschedule);
        // Existing completed/past blocks in this workspace are retained during a rebalance.
        var otherCourses=reschedule?commitments(owner,id,config,earliest,false):busy;
        ScheduleConflicts.check(config,otherCourses,earliest);
        var preview=Planner.plan(doc,config,busy,finished,earliest,reschedule?retainedMinutes(id,roadmap):Map.of());
        UUID previewId=UUID.randomUUID();
        Instant expires=clock.instant().plus(Duration.ofMinutes(30));
        String fingerprint=Json.sha256(json.write(List.of(id,roadmap,w.revision(),accountRevision,Planner.VERSION,settings,preview)));
        db.update("""
            INSERT INTO plan_previews(id,workspace_id,roadmap_id,workspace_revision,schedule_revision,planner_version,fingerprint,
              settings,schedule,feasible,reschedule,expires_at) VALUES(?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?)
            """,previewId,id,roadmap,w.revision(),accountRevision,Planner.VERSION,fingerprint,json.write(settings),
            json.write(preview),preview.feasible(),reschedule,utc(expires));
        var snap=new Snapshot(previewId,id,roadmap,w.revision(),accountRevision,Planner.VERSION,fingerprint,settings,preview,reschedule,expires);
        return view(snap,doc,0,120);
    }

    PreviewView getPreview(UUID owner,UUID w,UUID p,int page) {
        workspaces.owned(owner,w);
        if (page<0 || page>1000) throw Problem.invalid("Choose a valid preview page.");
        var snap=snapshot(w,p);
        return view(snap,roadmaps.get(w,snap.roadmap()),page*120,120);
    }

    private PreviewView view(Snapshot snap,Document doc,int offset,int limit) {
        var nodes=new HashMap<String,String>(); doc.nodes().forEach(n->nodes.put(n.id(),n.title()));
        var slots=snap.plan().slots().stream().skip(offset).limit(limit).map(s->{
            var map=new LinkedHashMap<String,Object>();
            map.put("date",s.date()); map.put("start",s.start()); map.put("end",s.end()); map.put("kind",s.kind());
            map.put("title",s.nodeId()==null?"Break":nodes.get(s.nodeId())); map.put("minutes",s.minutes());
            map.put("trackLabel",s.trackLabel());
            return (Map<String,Object>)map;
        }).toList();
        return new PreviewView(snap.id(),snap.revision(),snap.plannerVersion(),snap.fingerprint(),snap.expiresAt(),snap.reschedule(),
            snap.settings(),summary(snap.plan()),slots);
    }

    @Transactional
    Job queue(UUID owner,UUID w,UUID p,long revision,String key) {
        String request=Json.key(key);
        var workspace=workspaces.owned(owner,w,true);
        var existing=db.query("SELECT * FROM generation_jobs WHERE workspace_id=? AND request_key=?",this::job,w,request);
        if (!existing.isEmpty()) {
            if (!p.equals(existing.getFirst().previewId())) throw Problem.conflict("That request key belongs to a different preview.");
            return existing.getFirst();
        }
        var snap=snapshot(w,p);
        var previous=db.query("""
            SELECT * FROM generation_jobs WHERE workspace_id=? AND preview_id=? AND state IN ('QUEUED','RUNNING','SUCCEEDED')
            ORDER BY created_at LIMIT 1
            """,this::job,w,p);
        if (!previous.isEmpty()) return previous.getFirst();
        check(snap,workspace,workspaces.accountRevision(owner,false));
        if (revision!=workspace.revision()) throw Problem.stale();
        if (!snap.reschedule() && workspace.step()<6) throw Problem.invalid("Continue to Review before generating your schedule.");
        if (!snap.plan().feasible()) throw new Problem(422,"SCHEDULE_INFEASIBLE","Adjust the timetable and build a feasible preview first.");
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO generation_jobs(id,workspace_id,preview_id,request_key) VALUES(?,?,?,?)",id,w,p,request);
        return status(owner,w,id);
    }

    Job status(UUID owner,UUID w,UUID j) {
        workspaces.owned(owner,w);
        return db.query("SELECT * FROM generation_jobs WHERE workspace_id=? AND id=?",this::job,w,j)
            .stream().findFirst().orElseThrow(Problem::missing);
    }

    private record Claim(UUID id,UUID workspace,UUID preview,UUID owner,UUID token) {}
    @Scheduled(fixedDelay=850)
    void worker() {
        if (!enabled) return;
        db.update("""
            UPDATE generation_jobs SET state='FAILED',stage='Failed',error='Generation was interrupted. Refresh the preview and try again.'
            WHERE state='RUNNING' AND lease_until<now() AND attempts>=3
            """);
        UUID token=UUID.randomUUID();
        var jobs=db.query("""
            WITH next AS (
              SELECT id FROM generation_jobs WHERE state='QUEUED' OR (state='RUNNING' AND lease_until<now() AND attempts<3)
                ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE generation_jobs j SET state='RUNNING',stage='Validate',attempts=attempts+1,
              claim_token=?,lease_until=now()+interval '2 minutes' FROM next,workspaces w
              WHERE j.id=next.id AND w.id=j.workspace_id RETURNING j.id,j.workspace_id,j.preview_id,w.owner_id
            """,(r,n)->new Claim(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),
                r.getObject(4,UUID.class),token),token);
        if (jobs.isEmpty()) return;
        var claim=jobs.getFirst();
        try {
            var snap=snapshot(claim.workspace(),claim.preview());
            var workspace=workspaces.owned(claim.owner(),claim.workspace());
            if (alreadyActivated(claim)) return;
            check(snap,workspace,workspaces.accountRevision(claim.owner(),false));
            stage(claim,"Build schedule");
            var config=Planner.Settings.from(snap.settings(),clock,snap.reschedule());
            var doc=roadmaps.get(workspace.id(),snap.roadmap());
            var completed=snap.reschedule()?completed(workspace.id(),snap.roadmap()):Map.<String,Integer>of();
            stage(claim,"Verify");
            Planner.verify(snap.plan(),doc,config,completed,snap.reschedule()?retainedMinutes(snap.workspace(),snap.roadmap()):Map.of());
            stage(claim,"Activate");
            transaction.executeWithoutResult(t->activate(claim,snap,config));
        } catch (Problem e) {
            db.update("UPDATE generation_jobs SET state='FAILED',stage='Failed',error=?,lease_until=NULL WHERE id=? AND claim_token=? AND state='RUNNING'",
                e.getMessage(),claim.id(),token);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Generation {} interrupted ({})",claim.id(),e.getClass().getSimpleName());
            db.update("UPDATE generation_jobs SET lease_until=now() WHERE id=? AND claim_token=? AND state='RUNNING'",claim.id(),token);
        }
    }

    private void stage(Claim claim,String stage) {
        if (db.update("UPDATE generation_jobs SET stage=?,lease_until=now()+interval '2 minutes' WHERE id=? AND claim_token=? AND state='RUNNING'",
            stage,claim.id(),claim.token())!=1) throw Problem.conflict("Generation is already being processed.");
    }

    private boolean alreadyActivated(Claim claim) {
        var plans=db.query("SELECT id FROM plan_versions WHERE workspace_id=? AND preview_id=?",
            (r,n)->r.getObject(1,UUID.class),claim.workspace(),claim.preview());
        if (plans.isEmpty()) return false;
        success(claim,plans.getFirst()); return true;
    }

    private void activate(Claim claim,Snapshot snap,Planner.Settings config) {
        UUID owner=claim.owner(),w=claim.workspace();
        long rev=workspaces.accountRevision(owner,true);
        var workspace=workspaces.owned(owner,w,true);
        db.queryForObject("SELECT id FROM generation_jobs WHERE id=? AND claim_token=? AND state='RUNNING' FOR UPDATE",UUID.class,claim.id(),claim.token());
        if (alreadyActivated(claim)) return;
        check(snap,workspace,rev);
        Instant now=clock.instant();
        if (!snap.plan().slots().isEmpty()) {
            var first=snap.plan().slots().getFirst();
            if (first.start().isBefore(now)) throw Problem.stale();
            var busy=commitments(owner,w,config,now,snap.reschedule()).stream().sorted(Comparator.comparing(Planner.Commitment::start)).toList();
            int i=0;
            for (var slot:snap.plan().slots()) {
                while (i<busy.size() && !busy.get(i).end().isAfter(slot.start())) i++;
                if (i<busy.size() && busy.get(i).start().isBefore(slot.end())) throw Problem.stale();
            }
        }
        if (workspace.activePlanId()!=null) {
            studyLifecycle.pause(workspace,"plan-revised");
            db.update("UPDATE plan_versions SET status='SUPERSEDED' WHERE workspace_id=? AND id=?",w,workspace.activePlanId());
            db.update("""
                UPDATE schedule_slots s SET cancelled=true WHERE s.workspace_id=? AND s.plan_id=? AND s.starts_at>=now()
                AND NOT EXISTS (SELECT 1 FROM session_completions c WHERE c.session_id=s.id)
                """,w,workspace.activePlanId());
        }
        UUID plan=UUID.randomUUID();
        db.update("""
            INSERT INTO plan_versions(id,workspace_id,roadmap_id,preview_id,status,settings,summary)
            VALUES(?,?,?,?,'ACTIVE',?::jsonb,?::jsonb)
            """,plan,w,snap.roadmap(),snap.id(),json.write(snap.settings()),json.write(summary(snap.plan())));
        db.batchUpdate("""
            INSERT INTO schedule_slots(id,workspace_id,plan_id,roadmap_id,node_id,study_date,starts_at,ends_at,minutes,kind,track_label)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """,snap.plan().slots(),500,(ps,s)->{
            ps.setObject(1,UUID.randomUUID()); ps.setObject(2,w); ps.setObject(3,plan); ps.setObject(4,snap.roadmap()); ps.setString(5,s.nodeId());
            ps.setObject(6,s.date()); ps.setObject(7,utc(s.start())); ps.setObject(8,utc(s.end())); ps.setInt(9,s.minutes()); ps.setString(10,s.kind());
            ps.setString(11,s.trackLabel());
        });
        db.update("UPDATE workspaces SET status='ACTIVE',active_plan_id=?,revision=revision+1,updated_at=now() WHERE id=?",plan,w);
        db.update("UPDATE onboarding_drafts SET data=?::jsonb WHERE workspace_id=?",json.write(snap.settings()),w);
        db.update("UPDATE accounts SET schedule_revision=schedule_revision+1 WHERE id=?",owner);
        var rules=ReminderRules.from(snap.settings().path("timetable").path("reminders"),json);
        db.update("""
            INSERT INTO integration_preferences(workspace_id,owner_id,rules) VALUES(?,?,?::jsonb)
            ON CONFLICT(workspace_id) DO UPDATE SET rules=excluded.rules
            """,w,owner,json.write(rules));
        workspaces.integrationChanged(owner,w,"plan-activated");
        success(claim,plan);
    }

    private void success(Claim claim,UUID plan) {
        db.update("""
            UPDATE generation_jobs SET state='SUCCEEDED',stage='Ready',plan_id=?,lease_until=NULL,error=NULL
            WHERE id=? AND claim_token=? AND state='RUNNING'
            """,plan,claim.id(),claim.token());
    }

    @Transactional
    void lifecycle(UUID owner,UUID id,long expected,String target) {
        workspaces.accountRevision(owner,true);
        var w=workspaces.owned(owner,id,true);
        workspaces.expect(w,expected);
        if (target.equals("PAUSED") && !w.status().equals("ACTIVE") || w.status().equals("ARCHIVED"))
            throw Problem.conflict("This workspace cannot make that change.");
        studyLifecycle.pause(w,"workspace-"+target.toLowerCase(Locale.ROOT));
        db.update("UPDATE workspaces SET status=? WHERE id=?",target,id);
        workspaces.changed(id);
        if (w.activePlanId()!=null) {
            db.update("UPDATE accounts SET schedule_revision=schedule_revision+1 WHERE id=?",owner);
            workspaces.integrationChanged(owner,id,"workspace-"+target.toLowerCase(Locale.ROOT));
        }
    }

    private void check(Snapshot snap,Workspace w,long accountRevision) {
        if (snap.revision()!=w.revision() || snap.accountRevision()!=accountRevision || snap.plannerVersion()!=Planner.VERSION
            || !snap.expiresAt().isAfter(clock.instant()) || (snap.reschedule()
            ? !Set.of("ACTIVE","PAUSED").contains(w.status()) : !w.status().equals("DRAFT"))) throw Problem.stale();
    }

    private Map<String,Integer> completed(UUID workspace,UUID roadmap) {
        var result=new HashMap<String,Integer>();
        db.query("""
            SELECT s.node_id,sum(s.minutes) FROM schedule_slots s JOIN session_completions c ON c.session_id=s.id
            WHERE s.workspace_id=? AND s.roadmap_id=? GROUP BY s.node_id
            """,r->{result.put(r.getString(1),r.getInt(2));},workspace,roadmap);
        return result;
    }

    private Map<LocalDate,Integer> retainedMinutes(UUID workspace,UUID roadmap) {
        var result=new HashMap<LocalDate,Integer>();
        db.query("""
            SELECT s.study_date,sum(s.minutes)::integer FROM schedule_slots s
            JOIN session_completions c ON c.session_id=s.id
            WHERE s.workspace_id=? AND s.roadmap_id=? GROUP BY s.study_date
            """,r->{result.put(r.getObject(1,LocalDate.class),r.getInt(2));},workspace,roadmap);
        return result;
    }

    private List<Planner.Commitment> commitments(UUID owner,UUID current,Planner.Settings settings,Instant now,boolean reschedule) {
        var p=settings.preparation(); var zone=ZoneId.of(p.zone());
        var start=p.start().atStartOfDay(zone).toInstant(); var end=p.endExclusive().atStartOfDay(zone).toInstant();
        return db.query("""
            SELECT s.starts_at,s.ends_at,w.name FROM schedule_slots s JOIN workspaces w ON w.id=s.workspace_id
             WHERE w.owner_id=? AND s.plan_id=w.active_plan_id AND NOT s.cancelled AND w.status='ACTIVE'
               AND s.starts_at<? AND s.ends_at>?
               AND ((w.id<>? AND NOT EXISTS(SELECT 1 FROM session_completions c WHERE c.session_id=s.id))
                 OR (w.id=? AND ? AND (s.starts_at<? OR EXISTS(SELECT 1 FROM session_completions c WHERE c.session_id=s.id))))
             ORDER BY s.starts_at,s.ends_at
            """,(r,n)->new Planner.Commitment(instant(r,1),instant(r,2),r.getString(3)),owner,utc(end),utc(start),current,current,reschedule,utc(now));
    }

    private Snapshot snapshot(UUID workspace,UUID id) {
        return db.query("SELECT * FROM plan_previews WHERE workspace_id=? AND id=?",(r,n)->new Snapshot(
            id,workspace,r.getObject("roadmap_id",UUID.class),r.getLong("workspace_revision"),r.getLong("schedule_revision"),
            r.getInt("planner_version"),r.getString("fingerprint"),json.read(r.getString("settings")),
            json.as(json.read(r.getString("schedule")),Planner.Preview.class),r.getBoolean("reschedule"),instant(r,"expires_at")),workspace,id)
            .stream().findFirst().orElseThrow(Problem::missing);
    }
    private Job job(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new Job(r.getObject("id",UUID.class),r.getObject("preview_id",UUID.class),r.getString("state"),r.getString("stage"),
            r.getInt("attempts"),r.getObject("plan_id",UUID.class),r.getString("error"));
    }
    private ObjectNode summary(Planner.Preview preview) {
        var result=(ObjectNode)json.mapper.valueToTree(preview);
        result.put("slotCount",preview.slots().size()); result.remove("slots");
        return result;
    }
    static OffsetDateTime utc(Instant time) { return time.atOffset(ZoneOffset.UTC); }
    static Instant instant(java.sql.ResultSet r,int column) throws java.sql.SQLException {
        return r.getObject(column,OffsetDateTime.class).toInstant();
    }
    static Instant instant(java.sql.ResultSet r,String column) throws java.sql.SQLException {
        return r.getObject(column,OffsetDateTime.class).toInstant();
    }
}

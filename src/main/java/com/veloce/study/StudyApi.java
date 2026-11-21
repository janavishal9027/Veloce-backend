package com.veloce.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.veloce.common.*;
import com.veloce.workspace.WorkspaceStore.Workspace;
import java.time.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspaces/{w}/study")
public class StudyApi {
    private final StudyStore s;
    StudyApi(StudyStore store) { this.s=store; }

    private List<Map<String,Object>> slots(Workspace w,LocalDate date) {
        return s.ws.db.query("""
            SELECT x.id,x.node_id,x.starts_at,x.ends_at,x.minutes,x.kind,x.cancelled,x.track_label,n.title,n.kind AS activity_kind,n.outcome,
              EXISTS(SELECT 1 FROM session_completions c WHERE c.session_id=x.id) AS completed,
              (SELECT id FROM focus_sessions f WHERE f.session_id=x.id AND f.ended_at IS NULL) AS focus_id
            FROM schedule_slots x LEFT JOIN roadmap_nodes n USING(workspace_id,roadmap_id,node_id)
            WHERE x.workspace_id=? AND x.study_date=?
              AND (x.kind<>'BREAK' OR NOT x.cancelled)
              AND (x.plan_id=? OR EXISTS(SELECT 1 FROM session_completions c WHERE c.session_id=x.id)
                OR (x.kind='STUDY' AND NOT x.cancelled AND x.ends_at<=? AND EXISTS (
                  SELECT 1 FROM study_records work WHERE work.workspace_id=x.workspace_id AND work.roadmap_id=x.roadmap_id
                    AND work.study_date=x.study_date AND work.node_id=x.node_id AND work.record_type='WORK')))
            ORDER BY x.starts_at,x.id
            """,(r,n)->{
                var result=new LinkedHashMap<String,Object>();
                result.put("id",r.getObject("id")); result.put("nodeId",r.getString("node_id"));
                result.put("title",r.getString("kind").equals("BREAK")?"Break":r.getString("title"));
                result.put("kind",r.getString("kind")); result.put("activityKind",r.getString("activity_kind"));
                result.put("trackLabel",r.getString("track_label"));
                result.put("start",r.getObject("starts_at",OffsetDateTime.class).toInstant().toString());
                result.put("end",r.getObject("ends_at",OffsetDateTime.class).toInstant().toString());
                result.put("minutes",r.getInt("minutes")); result.put("completed",r.getBoolean("completed"));
                result.put("cancelled",r.getBoolean("cancelled")); result.put("focusId",r.getObject("focus_id"));
                result.put("outcome",r.getString("outcome")); return (Map<String,Object>)result;
            },w.id(),date,w.activePlanId(),s.clock.instant().atOffset(ZoneOffset.UTC));
    }

    @GetMapping("/day")
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    Map<String,Object> day(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@RequestParam String date) {
        var workspace=s.ws.owned(owner,w); UUID roadmap=s.roadmap(workspace);
        LocalDate selected=StudyRules.date(date);
        var slots=slots(workspace,selected);
        var entries=s.day(w,roadmap,selected);
        String fingerprint=s.fingerprint(entries);
        var reports=reports(w,roadmap,selected,0);
        var planned=slots.stream().filter(x->x.get("kind").equals("STUDY")&&!Boolean.TRUE.equals(x.get("cancelled")))
            .map(x->(String)x.get("nodeId")).distinct().toList();
        int plannedMinutes=slots.stream().filter(x->x.get("kind").equals("STUDY")&&!Boolean.TRUE.equals(x.get("cancelled")))
            .mapToInt(x->((Number)x.get("minutes")).intValue()).sum();
        var zone=s.zone(workspace);
        long focus=s.ws.db.queryForObject("""
            SELECT coalesce(sum(greatest(0,extract(epoch FROM
              (least(coalesce(ended_at,now()),?::timestamptz)-greatest(started_at,?::timestamptz))))),0)::bigint
            FROM focus_sessions WHERE workspace_id=? AND started_at<?::timestamptz
              AND coalesce(ended_at,now())>?::timestamptz
            """,Long.class,selected.plusDays(1).atStartOfDay(zone).toOffsetDateTime(),selected.atStartOfDay(zone).toOffsetDateTime(),
            w,selected.plusDays(1).atStartOfDay(zone).toOffsetDateTime(),selected.atStartOfDay(zone).toOffsetDateTime());
        var result=new LinkedHashMap<String,Object>();
        result.put("date",selected); result.put("today",s.today(workspace)); result.put("serverTime",s.clock.instant());
        result.put("roadmapId",roadmap); result.put("zone",zone.getId()); result.put("slots",slots); result.put("entries",entries);
        result.put("reports",reports); result.put("workFingerprint",fingerprint);
        var reportNodes=new LinkedHashSet<>(planned);entries.forEach(e->reportNodes.add(e.nodeId()));
        result.put("reportActivities",reportNodes.stream().flatMap(id->DailySections.cards(s.node(w,roadmap,id),slots.stream()
            .filter(slot->id.equals(slot.get("nodeId"))&&!Boolean.TRUE.equals(slot.get("cancelled"))).mapToInt(slot->((Number)slot.get("minutes")).intValue()).sum()).stream()).toList());
        result.put("dueCarryovers",s.ws.db.query("""
            SELECT * FROM study_records WHERE workspace_id=? AND roadmap_id=? AND record_type='CARRY'
              AND data->>'status'='OPEN' AND study_date<=? ORDER BY study_date,record_key LIMIT 30
            """,s::map,w,roadmap,selected));
        result.put("draftKeys",s.ws.db.queryForList("SELECT record_key FROM study_records WHERE workspace_id=? AND roadmap_id=? AND record_type='DRAFT' AND study_date=? AND data->'work' IS NOT NULL",String.class,w,roadmap,selected));
        var issues=StudyRules.reportIssues(entries,planned);
        result.put("reportFresh",issues.isEmpty()&&!reports.isEmpty()&&reports.getFirst().get("workFingerprint").equals(fingerprint));
        result.put("reportIssues",issues);
        result.put("plannedMinutes",plannedMinutes);
        result.put("reportedMinutes",entries.stream().mapToInt(e->e.data().path("actualMinutes").asInt()).sum());
        result.put("focusSeconds",focus);
        result.put("completedMinutes",slots.stream().filter(x->Boolean.TRUE.equals(x.get("completed"))).mapToInt(x->((Number)x.get("minutes")).intValue()).sum());
        return result;
    }

    @GetMapping("/activities")
    List<Map<String,Object>> activities(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,
        @RequestParam(defaultValue="") String kind,@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="0") int page) {
        var workspace=s.ws.owned(owner,w); var roadmap=s.roadmap(workspace);
        if (page<0||page>10000||query.length()>120||(!kind.isEmpty()&&!Set.of("LESSON","PRACTICE","PROJECT","REVIEW","ASSESSMENT").contains(kind)))
            throw Problem.invalid("Choose a valid activity filter.");
        return s.ws.db.query("""
            SELECT node_id,title,kind,outcome,estimated_minutes,resources FROM roadmap_nodes
            WHERE workspace_id=? AND roadmap_id=? AND kind<>'MODULE'
              AND (?='' OR kind=?) AND lower(node_id||' '||title||' '||outcome) LIKE ?
            ORDER BY ordinal LIMIT 60 OFFSET ?
            """,(r,n)->Map.of("id",r.getString(1),"title",r.getString(2),"kind",r.getString(3),"outcome",r.getString(4),
                "minutes",r.getInt(5),"resources",s.json.read(r.getString(6))),w,roadmap,kind,kind,"%"+query.toLowerCase(Locale.ROOT)+"%",page*60);
    }

    private String type(String value) {
        if (!Set.of("WORK","PROJECT","CARRY","PRACTICE","DRAFT").contains(value)) throw Problem.invalid("Choose a study record type.");
        return value;
    }

    @GetMapping("/records/{type}")
    Object records(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable String type,
        @RequestParam(required=false) String key,@RequestParam(required=false) String date,@RequestParam(defaultValue="0") int page) {
        var workspace=s.ws.owned(owner,w); var roadmap=s.roadmap(workspace);
        if(type.equals("DAILY")) {
            var parts=dailyKey(key);s.node(w,roadmap,parts[1]);
            return s.get(w,roadmap,"WORK",parts[0]+"/"+parts[1]).map(row->(Object)new StudyStore.Record(
                "DAILY",key,row.nodeId(),row.date(),row.version(),dailyData(row.data(),parts[2]),row.updatedAt())).orElse(Map.of());
        }
        type(type);
        if (key!=null) return s.get(w,roadmap,type,key).map(x->(Object)x).orElse(Map.of());
        return s.list(w,roadmap,type,date==null?null:StudyRules.date(date),page);
    }

    @GetMapping("/history/{type}")
    List<Map<String,Object>> history(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable String type,
        @RequestParam String key,@RequestParam(defaultValue="0") int page) {
        var workspace=s.ws.owned(owner,w);
        if(type.equals("DAILY")) {
            var parts=dailyKey(key);s.node(w,s.roadmap(workspace),parts[1]);
            if(page<0||page>10000)throw Problem.invalid("Choose a valid history page.");
            return s.ws.db.query("""
                WITH parts AS (SELECT version,recorded_at,
                  CASE WHEN jsonb_typeof(data->'dailySections')='object' THEN data->'dailySections'->? ELSE CASE WHEN ?='CONCEPT' THEN data END END AS part
                  FROM study_record_history WHERE workspace_id=? AND roadmap_id=? AND record_type='WORK' AND record_key=?),
                changes AS (SELECT *,lag(part) OVER(ORDER BY version) AS previous FROM parts)
                SELECT version,part,recorded_at FROM changes WHERE part IS NOT NULL AND part IS DISTINCT FROM previous
                ORDER BY version DESC LIMIT 30 OFFSET ?
                """,(r,n)->Map.<String,Object>of("version",r.getLong(1),"data",s.json.read(r.getString(2)),"recordedAt",r.getObject(3,OffsetDateTime.class).toInstant().toString()),
                parts[2],parts[2],w,s.roadmap(workspace),parts[0]+"/"+parts[1],page*30);
        }
        return s.history(w,s.roadmap(workspace),type(type),key,page);
    }

    private String[] dailyKey(String key) {
        if(key==null)throw Problem.invalid("Choose a daily report section.");
        var parts=key.split("/",-1);
        if(parts.length!=3||!DailySections.TITLES.containsKey(parts[2]))throw Problem.invalid("Choose a daily report section.");
        StudyRules.date(parts[0]);return parts;
    }
    private JsonNode dailyData(JsonNode data,String section) {
        if(data.path("dailySections").isObject())return data.path("dailySections").path(section);
        return section.equals("CONCEPT")?data:s.json.object();
    }

    @PutMapping("/work/{node}")
    @Transactional
    StudyStore.Record work(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable String node,@RequestBody JsonNode input) {
        var workspace=s.ws.owned(owner,w,true); s.writable(workspace); var roadmap=s.roadmap(workspace);
        var activity=s.node(w,roadmap,node);
        LocalDate date=StudyRules.date(Json.text(input,"date",10,true));
        if(date.isBefore(s.historyStart(workspace).date()))
            throw Problem.invalid("Choose a work date on or after this workspace's preparation start date.");
        var clean=StudyRules.work(s.json,input.path("data"),false);
        String dailySection=input.path("dailySection").asText("");
        var previous=s.get(w,roadmap,"WORK",date+"/"+node);
        if(!dailySection.isEmpty()) {
            if(!DailySections.structured(activity))throw Problem.invalid("This activity uses a single work update.");
            var replay=s.ws.db.query("SELECT record_type,record_key,data,roadmap_id FROM study_record_history WHERE workspace_id=? AND mutation_id=?",
                (r,n)->Map.of("type",r.getString(1),"key",r.getString(2),"data",s.json.read(r.getString(3)),"roadmap",r.getObject(4,UUID.class)),w,StudyRules.mutation(input));
            if(!replay.isEmpty()) {
                var old=replay.getFirst();var part=dailyData((JsonNode)old.get("data"),dailySection).deepCopy();
                if(part instanceof ObjectNode object)object.remove("sectionVersion");
                if(!old.get("roadmap").equals(roadmap)||!old.get("type").equals("WORK")||!old.get("key").equals(date+"/"+node)||!part.equals(clean))
                    throw Problem.conflict("That save identifier was already used for different work.");
                return previous.orElseThrow(Problem::missing);
            }
            dailyWritable(workspace,date,clean.path("correctionReason").asText());
            clean=DailySections.merge(s.json,previous.map(StudyStore.Record::data).orElse(s.json.object()),dailySection,clean,activity.get("title").toString());
        } else if(DailySections.structured(activity)||previous.isPresent()&&previous.get().data().has("dailySections")) {
            throw Problem.conflict("Open Daily report and update the individual work section to keep all saved sections.");
        }
        clean.put("activityTitle",activity.get("title").toString());
        if (date.isAfter(s.today(workspace)) && (clean.path("actualMinutes").asInt()>0||!clean.path("status").asText().equals("NOT_STARTED")))
            throw Problem.conflict("Future activities can be planned, but cannot be reported as work already performed.");
        int total=s.ws.db.queryForObject("""
            SELECT coalesce(sum((data->>'actualMinutes')::int),0) FROM study_records
            WHERE workspace_id=? AND roadmap_id=? AND record_type='WORK' AND study_date=? AND node_id<>?
            """,Integer.class,w,roadmap,date,node);
        if (total+clean.path("actualMinutes").asInt()>1440) throw Problem.invalid("A day's reported work cannot exceed 1,440 minutes.");
        long bytes=s.ws.db.queryForObject("""
            SELECT coalesce(sum(octet_length(data::text)),0)+(SELECT octet_length(?::jsonb::text))
            FROM study_records WHERE workspace_id=? AND roadmap_id=? AND record_type='WORK' AND study_date=? AND node_id<>?
            """,Long.class,s.json.write(clean),w,roadmap,date,node);
        if (bytes>1_100_000) throw Problem.invalid("This day's detailed work would exceed the supported report size. Shorten large pasted outputs before saving.");
        var saved=s.save(w,roadmap,"WORK",date+"/"+node,node,date,clean,StudyRules.version(input),StudyRules.mutation(input));
        clearPublishedDraft(w,roadmap,(dailySection.isEmpty()?"WORK/":"DAILY/")+date+"/"+node+(dailySection.isEmpty()?"":"/"+dailySection),input);
        return saved;
    }

    private void dailyWritable(Workspace workspace,LocalDate date,String correction) {
        if(date.isAfter(s.today(workspace)))throw Problem.conflict("Future lessons are read only. Save a draft until the study day arrives.");
        if(date.isBefore(s.today(workspace))&&correction.trim().length()<10)
            throw Problem.conflict("A past-day correction requires a reason of at least 10 characters.");
        if(date.equals(s.today(workspace))&&!Boolean.TRUE.equals(s.ws.db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM study_presence WHERE workspace_id=? AND study_date=? AND ended_at IS NULL)",Boolean.class,workspace.id(),date)))
            throw Problem.conflict("Check in from Today before saving today's work. You can keep a draft until you check in.");
    }

    @PutMapping("/projects/{node}/{section}")
    @Transactional
    StudyStore.Record project(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable String node,
        @PathVariable String section,@RequestBody JsonNode input) {
        var workspace=s.ws.owned(owner,w,true); s.writable(workspace); var roadmap=s.roadmap(workspace);
        var project=new ProjectCatalog(s).find(workspace,node);
        if (!StudyRules.SECTIONS.contains(section))
            throw Problem.invalid("Choose a project activity and a valid project section.");
        var data=StudyRules.work(s.json,input.path("data"),false,43_200);
        data.put("activityTitle",project.title());
        var saved=s.save(w,roadmap,"PROJECT",node+"/"+section,project.storageNodeId(),s.today(workspace),data,StudyRules.version(input),StudyRules.mutation(input));
        clearPublishedDraft(w,roadmap,"PROJECT/"+node+"/"+section,input);
        return saved;
    }

    private void clearPublishedDraft(UUID w,UUID roadmap,String key,JsonNode input) {
        var draft=s.get(w,roadmap,"DRAFT",key);
        if (draft.isPresent()&&input.path("draftVersion").isIntegralNumber()
            &&draft.get().version()==input.path("draftVersion").asLong()) {
            s.ws.db.update("DELETE FROM study_records WHERE workspace_id=? AND roadmap_id=? AND record_type='DRAFT' AND record_key=?",w,roadmap,key);
        }
    }

    @DeleteMapping("/drafts")
    @Transactional
    void discardDraft(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@RequestParam String key,@RequestBody JsonNode input) {
        var workspace=s.ws.owned(owner,w,true);s.writable(workspace);var roadmap=s.roadmap(workspace);
        var record=s.get(w,roadmap,"DRAFT",key).orElseThrow(Problem::missing);
        if (record.version()!=StudyRules.version(input)) throw Problem.conflict("This draft changed in another view.");
        s.ws.db.update("DELETE FROM study_records WHERE workspace_id=? AND roadmap_id=? AND record_type='DRAFT' AND record_key=?",w,roadmap,key);
    }

    @PutMapping("/drafts")
    @Transactional
    StudyStore.Record draft(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@RequestBody JsonNode input) {
        var workspace=s.ws.owned(owner,w,true); s.writable(workspace); var roadmap=s.roadmap(workspace);
        String node=Json.text(input,"nodeId",80,true);
        String target=Json.text(input,"targetType",12,true);
        LocalDate date=StudyRules.date(Json.text(input,"date",10,true));
        String key;
        String storageNode=node;
        if (target.equals("WORK")) { s.node(w,roadmap,node); key=date+"/"+node; }
        else if(target.equals("DAILY")&&DailySections.TITLES.containsKey(input.path("section").asText())) {
            if(!DailySections.structured(s.node(w,roadmap,node)))throw Problem.invalid("This activity uses a single work update.");
            key=date+"/"+node+"/"+input.path("section").asText();
        }
        else if (target.equals("PROJECT")&&StudyRules.SECTIONS.contains(input.path("section").asText())) {
            storageNode=new ProjectCatalog(s).find(workspace,node).storageNodeId();
            key=node+"/"+input.path("section").asText();
        }
        else throw Problem.invalid("Choose a valid work or project draft.");
        var data=s.json.object().put("targetType",target).put("baseVersion",StudyRules.number(input,"baseVersion",0,Integer.MAX_VALUE,0));
        data.set("work",StudyRules.work(s.json,input.path("data"),true,target.equals("PROJECT")?43_200:1440));
        return s.save(w,roadmap,"DRAFT",target+"/"+key,storageNode,date,data,StudyRules.version(input),StudyRules.mutation(input));
    }

    @PutMapping("/carryover/{id}")
    @Transactional
    StudyStore.Record carry(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable UUID id,@RequestBody JsonNode input) {
        var workspace=s.ws.owned(owner,w,true); s.writable(workspace); var roadmap=s.roadmap(workspace);
        return new StudyCarryover(s).save(workspace,id.toString(),input);
    }

    private List<Map<String,Object>> reports(UUID w,UUID roadmap,LocalDate date,int page) {
        if (page<0||page>10000) throw Problem.invalid("Choose a report page.");
        return s.ws.db.query("""
            SELECT study_date,version,work_fingerprint,data,submitted_at FROM study_reports
            WHERE workspace_id=? AND roadmap_id=? AND (?::date IS NULL OR study_date=?::date)
            ORDER BY study_date DESC,version DESC LIMIT 30 OFFSET ?
            """,(r,n)->Map.<String,Object>of("date",r.getObject(1,LocalDate.class),"version",r.getLong(2),
                "workFingerprint",r.getString(3),"data",s.json.read(r.getString(4)),
                "submittedAt",r.getObject(5,OffsetDateTime.class).toInstant().toString()),w,roadmap,date,date,page*30);
    }

    @GetMapping("/reports")
    List<Map<String,Object>> reports(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,
        @RequestParam(required=false) String date,@RequestParam(defaultValue="0") int page) {
        var workspace=s.ws.owned(owner,w);
        return reports(w,s.roadmap(workspace),date==null?null:StudyRules.date(date),page);
    }

    @PostMapping("/reports")
    @Transactional
    Map<String,Object> submit(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@RequestBody JsonNode input) {
        var workspace=s.ws.owned(owner,w,true); s.writable(workspace); var roadmap=s.roadmap(workspace);
        LocalDate date=StudyRules.date(Json.text(input,"date",10,true));
        if (date.isAfter(s.today(workspace))) throw Problem.conflict("A future day cannot be submitted as completed study.");
        if(date.isBefore(s.historyStart(workspace).date()))
            throw Problem.invalid("Choose a report date on or after this workspace's preparation start date.");
        UUID mutation=StudyRules.mutation(input);
        String expected=Json.text(input,"workFingerprint",64,true);
        var data=s.json.object().put("summary",Json.text(input,"summary",4000,true))
            .put("blockers",Json.text(input,"blockers",4000,false)).put("nextSteps",Json.text(input,"nextSteps",4000,false));
        // Resolve a retry against its original snapshot, even after later work/report updates.
        var replay=s.ws.db.query("SELECT study_date,version,work_fingerprint,data,submitted_at FROM study_reports WHERE workspace_id=? AND roadmap_id=? AND mutation_id=?",
            (r,n)->Map.<String,Object>of("date",r.getObject(1,LocalDate.class),"version",r.getLong(2),"workFingerprint",r.getString(3),
                "data",s.json.read(r.getString(4)),"submittedAt",r.getObject(5,OffsetDateTime.class).toInstant().toString()),w,roadmap,mutation);
        if (!replay.isEmpty()) {
            var prior=replay.getFirst();var snapshot=(JsonNode)prior.get("data");
            if (!prior.get("date").equals(date)||!prior.get("workFingerprint").equals(expected)
                ||!data.path("summary").equals(snapshot.path("summary"))||!data.path("blockers").equals(snapshot.path("blockers"))
                ||!data.path("nextSteps").equals(snapshot.path("nextSteps")))
                throw Problem.conflict("That report identifier belongs to a different submission.");
            return prior;
        }
        var entries=s.day(w,roadmap,date); String fingerprint=s.fingerprint(entries);
        if(entries.stream().anyMatch(e->e.data().has("dailySections"))) {
            String correction=Json.text(input,"correctionReason",1000,false);dailyWritable(workspace,date,correction);
            data.put("correctionReason",correction);
        }
        if (!fingerprint.equals(expected)) throw Problem.conflict("Work changed after this report was opened. Reload the day and review the latest work.");
        var planned=slots(workspace,date).stream().filter(x->x.get("kind").equals("STUDY")&&!Boolean.TRUE.equals(x.get("cancelled")))
            .map(x->(String)x.get("nodeId")).distinct().toList();
        var issues=StudyRules.reportIssues(entries,planned);
        if (!issues.isEmpty()) throw new Problem(422,"REPORT_INCOMPLETE",String.join(" ",issues.stream().limit(8).toList()),
            issues.stream().limit(80).map(x->new com.veloce.roadmap.Roadmap.Issue("work",x)).toList());
        // Normalize numeric nodes exactly as PostgreSQL JSON will be read back (int/long versions).
        data.set("entries",s.json.read(s.json.write(entries)));
        if (s.json.write(data).length()>1_200_000) throw Problem.invalid("This report exceeds the supported snapshot size.");
        var previous=reports(w,roadmap,date,0);
        long version=previous.isEmpty()?0:((Number)previous.getFirst().get("version")).longValue();
        if (StudyRules.version(input)!=version) throw Problem.conflict("A newer report was submitted in another view.");
        if (!previous.isEmpty()&&previous.getFirst().get("data").equals(data)) return previous.getFirst();
        s.ws.db.update("""
            INSERT INTO study_reports(workspace_id,roadmap_id,study_date,version,mutation_id,work_fingerprint,data)
            VALUES(?,?,?,?,?,?,?::jsonb)
            """,w,roadmap,date,version+1,mutation,fingerprint,s.json.write(data));
        return reports(w,roadmap,date,0).getFirst();
    }

    @GetMapping("/overview")
    Map<String,Object> overview(@AuthenticationPrincipal UUID owner,@PathVariable UUID w) {
        var workspace=s.ws.owned(owner,w); var roadmap=s.roadmap(workspace);
        var days=s.ws.db.query("""
            SELECT study_date,count(*) AS activities,sum((data->>'actualMinutes')::int) AS minutes,
              count(*) FILTER(WHERE data->>'status'='DONE') AS done,
              count(*) FILTER(WHERE data->>'status'='BLOCKED') AS blocked
            FROM study_records WHERE workspace_id=? AND roadmap_id=? AND record_type='WORK'
            GROUP BY study_date ORDER BY study_date DESC LIMIT 366
            """,(r,n)->Map.of("date",r.getObject(1,LocalDate.class),"activities",r.getInt(2),
                "minutes",r.getInt(3),"done",r.getInt(4),"blocked",r.getInt(5)),w,roadmap);
        var kinds=s.ws.db.query("""
            SELECT n.kind,count(*) AS count FROM study_records r JOIN roadmap_nodes n
              ON n.workspace_id=r.workspace_id AND n.roadmap_id=r.roadmap_id AND n.node_id=r.node_id
            WHERE r.workspace_id=? AND r.roadmap_id=? AND r.record_type='WORK' AND r.data->>'status'='DONE'
            GROUP BY n.kind ORDER BY n.kind
            """,(r,n)->Map.of("kind",r.getString(1),"count",r.getInt(2)),w,roadmap);
        var counts=s.ws.db.query("SELECT record_type,count(*) FROM study_records WHERE workspace_id=? AND roadmap_id=? AND record_type<>'DRAFT' GROUP BY record_type",
            (r,n)->Map.of("type",r.getString(1),"count",r.getInt(2)),w,roadmap);
        return Map.of("days",days,"kinds",kinds,"recordCounts",counts,"serverTime",s.clock.instant());
    }
}

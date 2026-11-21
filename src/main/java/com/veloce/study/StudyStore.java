package com.veloce.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloce.common.*;
import com.veloce.workspace.WorkspaceStore;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class StudyStore {
    public record Record(String type,String key,String nodeId,LocalDate date,long version,JsonNode data,String updatedAt) {}
    public final WorkspaceStore ws;
    public final Json json;
    public final Clock clock;
    StudyStore(WorkspaceStore ws,Clock clock) { this.ws=ws; this.json=ws.json; this.clock=clock; }
    public UUID roadmap(WorkspaceStore.Workspace w) {
        if (w.activePlanId()==null) throw Problem.conflict("Activate this workspace's timetable before recording study work.");
        return ws.db.queryForObject("SELECT roadmap_id FROM plan_versions WHERE workspace_id=? AND id=?",UUID.class,w.id(),w.activePlanId());
    }
    public JsonNode preparation(WorkspaceStore.Workspace w) { return w.data().path("preparation"); }
    public ZoneId zone(WorkspaceStore.Workspace w) {
        try { return ZoneId.of(preparation(w).path("zone").asText("UTC")); }
        catch (DateTimeException e) { return ZoneOffset.UTC; }
    }
    public LocalDate today(WorkspaceStore.Workspace w) { return LocalDate.now(clock.withZone(zone(w))); }

    record HistoryStart(LocalDate date, ZoneId zone) {}

    HistoryStart historyStart(WorkspaceStore.Workspace w) {
        var plans=ws.db.query("""
            SELECT settings#>>'{preparation,startDate}',settings#>>'{preparation,zone}' FROM plan_versions
            WHERE workspace_id=? AND settings#>>'{preparation,startDate}' IS NOT NULL
            ORDER BY settings#>>'{preparation,startDate}',activated_at LIMIT 1
            """,(row,index)->new HistoryStart(StudyRules.date(row.getString(1)),ZoneId.of(Objects.requireNonNullElse(row.getString(2),"UTC"))),w.id());
        if (!plans.isEmpty()) return plans.getFirst();
        var start=preparation(w).path("startDate").asText();
        if (start.isEmpty()) throw Problem.conflict("Set a preparation start date before viewing history.");
        return new HistoryStart(StudyRules.date(start),zone(w));
    }
    public void writable(WorkspaceStore.Workspace w) {
        if (w.status().equals("ARCHIVED")) throw Problem.conflict("Archived workspaces are read only.");
    }
    public Map<String,Object> node(UUID w,UUID roadmap,String id) {
        if (id==null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")) throw Problem.invalid("Choose a valid activity.");
        return ws.db.query("SELECT node_id AS id,title,kind,outcome,estimated_minutes AS minutes,resources FROM roadmap_nodes WHERE workspace_id=? AND roadmap_id=? AND node_id=? AND kind<>'MODULE'",
            (r,n)->Map.<String,Object>of("id",r.getString("id"),"title",r.getString("title"),"kind",r.getString("kind"),
                "outcome",r.getString("outcome"),"minutes",r.getInt("minutes"),"resources",json.read(r.getString("resources"))),
            w,roadmap,id).stream().findFirst().orElseThrow(Problem::missing);
    }
    public Record map(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new Record(r.getString("record_type"),r.getString("record_key"),r.getString("node_id"),
            r.getObject("study_date",LocalDate.class),r.getLong("version"),json.read(r.getString("data")),
            r.getObject("updated_at",OffsetDateTime.class).toInstant().toString());
    }
    public Optional<Record> get(UUID w,UUID roadmap,String type,String key) {
        return ws.db.query("SELECT * FROM study_records WHERE workspace_id=? AND roadmap_id=? AND record_type=? AND record_key=?",
            this::map,w,roadmap,type,key).stream().findFirst();
    }
    public List<Record> list(UUID w,UUID roadmap,String type,LocalDate date,int page) {
        if (page<0 || page>10000) throw Problem.invalid("Choose a valid page.");
        return ws.db.query("""
            SELECT * FROM study_records WHERE workspace_id=? AND roadmap_id=? AND record_type=?
              AND (?::date IS NULL OR study_date=?::date) ORDER BY updated_at DESC,record_key LIMIT 100 OFFSET ?
            """,this::map,w,roadmap,type,date,date,page*100);
    }
    public List<Record> day(UUID w,UUID roadmap,LocalDate date) {
        long bytes=ws.db.queryForObject("SELECT coalesce(sum(octet_length(data::text)),0) FROM study_records WHERE workspace_id=? AND roadmap_id=? AND record_type='WORK' AND study_date=?",
            Long.class,w,roadmap,date);
        if (bytes>1_100_000) throw Problem.invalid("This day's detailed records exceed the report size limit. Shorten large pasted outputs before loading the full day.");
        return ws.db.query("""
            SELECT * FROM study_records WHERE workspace_id=? AND roadmap_id=? AND record_type='WORK' AND study_date=?
            ORDER BY node_id
            """,this::map,w,roadmap,date);
    }
    public String fingerprint(List<Record> entries) {
        return Json.sha256(json.write(entries.stream().sorted(Comparator.comparing(Record::key)).map(e->List.of(e.key(),e.version())).toList()));
    }
    public Record save(UUID w,UUID roadmap,String type,String key,String nodeId,LocalDate date,JsonNode data,long expected,UUID mutation) {
        int max=type.equals("PRACTICE")?1_200_000:type.equals("WORK")&&data.has("dailySections")?360_000:60_000;
        if (json.write(data).length()>max) throw Problem.invalid("This study record exceeds the supported size. Shorten large pasted content before saving.");
        var replay=ws.db.query("SELECT roadmap_id,record_type,record_key,data FROM study_record_history WHERE workspace_id=? AND mutation_id=?",
            (r,n)->Map.of("roadmap",r.getObject(1,UUID.class),"type",r.getString(2),"key",r.getString(3),"data",json.read(r.getString(4))),w,mutation);
        if (!replay.isEmpty()) {
            var prior=replay.getFirst();
            if (!prior.get("roadmap").equals(roadmap)||!prior.get("type").equals(type)||!prior.get("key").equals(key)||!prior.get("data").equals(data))
                throw Problem.conflict("That save identifier was already used for different work.");
            return get(w,roadmap,type,key).orElseThrow(Problem::missing);
        }
        long version=get(w,roadmap,type,key).map(Record::version).orElse(0L);
        if (version!=expected) throw Problem.conflict("This record changed in another view. Your draft is retained; reload and compare before saving.");
        long next=version+1;
        ws.db.update("""
            INSERT INTO study_records(workspace_id,roadmap_id,record_type,record_key,node_id,study_date,version,data)
            VALUES(?,?,?,?,?,?,?,?::jsonb) ON CONFLICT(workspace_id,roadmap_id,record_type,record_key)
            DO UPDATE SET version=excluded.version,data=excluded.data,study_date=excluded.study_date,updated_at=now()
            """,w,roadmap,type,key,nodeId,date,next,json.write(data));
        ws.db.update("""
            INSERT INTO study_record_history(workspace_id,roadmap_id,record_type,record_key,node_id,study_date,version,mutation_id,data)
            VALUES(?,?,?,?,?,?,?,?,?::jsonb)
            """,w,roadmap,type,key,nodeId,date,next,mutation,json.write(data));
        return get(w,roadmap,type,key).orElseThrow();
    }
    public List<Map<String,Object>> history(UUID w,UUID roadmap,String type,String key,int page) {
        if (page<0||page>10000) throw Problem.invalid("Choose a valid history page.");
        return ws.db.query("""
            SELECT version,data,recorded_at FROM study_record_history
            WHERE workspace_id=? AND roadmap_id=? AND record_type=? AND record_key=?
            ORDER BY version DESC LIMIT 30 OFFSET ?
            """,(r,n)->Map.of("version",r.getLong(1),"data",json.read(r.getString(2)),
                "recordedAt",r.getObject(3,OffsetDateTime.class).toInstant().toString()),w,roadmap,type,key,page*30);
    }
}

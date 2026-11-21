package com.veloce.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloce.common.Problem;
import com.veloce.workspace.WorkspaceStore.Workspace;
import java.time.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspaces/{w}/study/history")
public class StudyHistoryApi {
    private static final Set<String> TYPES=Set.of("WORK","PROJECT","REPORT");
    private static final int PAGE_SIZE=30;
    private final StudyStore s;
    StudyHistoryApi(StudyStore store) { this.s=store; }

    record Range(LocalDate from,LocalDate to) { boolean empty() { return from.isAfter(to); } }
    record Event(UUID roadmapId,String type,String key,String nodeId,LocalDate date,long version,
        String recordedAt,String title,String section,String status,int actualMinutes) {}
    record Page(LocalDate startDate,LocalDate today,LocalDate from,LocalDate to,String zone,
        String asOf,List<Event> events,boolean hasMore) {}
    record Update(Event event,JsonNode data,JsonNode previous,List<String> changedFields) {}
    private record Snapshot(Event event,JsonNode data) {}

    static Range range(LocalDate start,LocalDate today,String from,String to) {
        LocalDate requestedFrom=from==null?start:StudyRules.date(from);
        LocalDate requestedTo=to==null?today:StudyRules.date(to);
        if (from!=null&&to!=null&&requestedFrom.isAfter(requestedTo))
            throw Problem.invalid("Choose an end date on or after the start date.");
        return new Range(requestedFrom.isBefore(start)?start:requestedFrom,requestedTo.isAfter(today)?today:requestedTo);
    }
    static Instant cutoff(String value,Instant now) {
        if (value==null) return now;
        try { var instant=Instant.parse(value); return instant.isAfter(now)?now:instant; }
        catch (DateTimeException e) { throw Problem.invalid("Choose a valid history snapshot."); }
    }
    private LocalDate start(Workspace workspace) {
        return s.historyStart(workspace).date();
    }

    // History spans all roadmap versions in this workspace. Each saved revision is immutable.
    private static final String EVENTS="""
        WITH events AS (
          SELECT workspace_id,roadmap_id,record_type,record_key,node_id,study_date,version,data,recorded_at
          FROM study_record_history
          WHERE workspace_id=? AND record_type IN ('WORK','PROJECT')
            AND study_date BETWEEN ? AND ? AND recorded_at>=? AND recorded_at<=?
          UNION ALL
          SELECT workspace_id,roadmap_id,'REPORT',study_date::text,NULL::varchar,study_date,version,data,submitted_at
          FROM study_reports
          WHERE workspace_id=? AND study_date BETWEEN ? AND ? AND submitted_at>=? AND submitted_at<=?
        )
        """;
    private static final String PROJECTION="""
        SELECT e.roadmap_id,e.record_type,e.record_key,e.node_id,e.study_date,e.version,e.recorded_at,
          CASE WHEN e.record_type='REPORT' THEN 'Daily report'
            ELSE coalesce(nullif(e.data->>'activityTitle',''),n.title,e.node_id,'Work update') END AS title,
          CASE WHEN e.record_type='PROJECT' THEN split_part(e.record_key,'/',2) ELSE '' END AS section,
          CASE WHEN e.record_type='REPORT' THEN 'SUBMITTED' ELSE coalesce(e.data->>'status','NOT_STARTED') END AS status,
          CASE WHEN e.record_type='REPORT' THEN 0 ELSE coalesce((e.data->>'actualMinutes')::int,0) END AS actual_minutes
        FROM events e LEFT JOIN roadmap_nodes n
          ON n.workspace_id=e.workspace_id AND n.roadmap_id=e.roadmap_id AND n.node_id=e.node_id
        """;
    private List<Object> parameters(Workspace workspace,Range range,Instant asOf) {
        var start=s.historyStart(workspace);
        var beginning=start.date().atStartOfDay(start.zone()).toOffsetDateTime();
        var end=asOf.atOffset(ZoneOffset.UTC);
        return new ArrayList<>(List.of(workspace.id(),range.from(),range.to(),beginning,end,
            workspace.id(),range.from(),range.to(),beginning,end));
    }
    private Event event(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Event(r.getObject("roadmap_id",UUID.class),r.getString("record_type"),r.getString("record_key"),
            r.getString("node_id"),r.getObject("study_date",LocalDate.class),r.getLong("version"),
            r.getObject("recorded_at",OffsetDateTime.class).toInstant().toString(),r.getString("title"),
            r.getString("section"),r.getString("status"),r.getInt("actual_minutes"));
    }

    @GetMapping
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    Page history(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,
        @RequestParam(required=false) String from,@RequestParam(required=false) String to,
        @RequestParam(defaultValue="ALL") String type,@RequestParam(defaultValue="0") int page,
        @RequestParam(required=false) String asOf) {
        var workspace=s.ws.owned(owner,w);
        if ((!type.equals("ALL")&&!TYPES.contains(type))||page<0||page>10000)
            throw Problem.invalid("Choose a valid history filter and page.");
        var start=start(workspace); var today=s.today(workspace);
        var range=range(start,today,from,to); var snapshot=cutoff(asOf,s.clock.instant());
        List<Event> events=List.of();
        if (!range.empty()) {
            var args=parameters(workspace,range,snapshot);
            args.add(type); args.add(type); args.add(page*PAGE_SIZE);
            events=s.ws.db.query(EVENTS+PROJECTION+"""
                WHERE (?='ALL' OR e.record_type=?)
                ORDER BY e.recorded_at DESC,e.roadmap_id DESC,e.record_type,e.record_key,e.version DESC
                LIMIT 31 OFFSET ?
                """,(r,n)->event(r),args.toArray());
        }
        return new Page(start,today,range.from(),range.to(),s.zone(workspace).getId(),snapshot.toString(),
            List.copyOf(events.subList(0,Math.min(events.size(),PAGE_SIZE))),events.size()>PAGE_SIZE);
    }

    @GetMapping("/event")
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    Update update(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@RequestParam UUID roadmapId,
        @RequestParam String type,@RequestParam String key,@RequestParam long version) {
        var workspace=s.ws.owned(owner,w);
        if (!TYPES.contains(type)||key.isBlank()||key.length()>200||version<1)
            throw Problem.invalid("Choose a valid work update.");
        var bounds=new Range(start(workspace),s.today(workspace));
        if (bounds.empty()) throw Problem.missing();
        var args=parameters(workspace,bounds,s.clock.instant());
        args.add(roadmapId); args.add(type); args.add(key); args.add(version);
        var rows=s.ws.db.query(EVENTS+PROJECTION.replace("SELECT e.roadmap_id,","SELECT e.data,e.roadmap_id,")+"""
            WHERE e.roadmap_id=? AND e.record_type=? AND e.record_key=? AND e.version=?
            """,(r,n)->new Snapshot(event(r),s.json.read(r.getString("data"))),args.toArray());
        var selected=rows.stream().findFirst().orElseThrow(Problem::missing);
        // Fetch one earlier revision only; reports and work can contain large saved evidence.
        var earlier=s.ws.db.query(EVENTS+"""
            SELECT data FROM events WHERE roadmap_id=? AND record_type=? AND record_key=? AND version<?
            ORDER BY version DESC LIMIT 1
            """,(r,n)->s.json.read(r.getString("data")),args.toArray());
        var previous=earlier.isEmpty()?null:earlier.getFirst();
        return new Update(selected.event(),selected.data(),previous,changes(previous,selected.data()));
    }

    static List<String> changes(JsonNode previous,JsonNode current) {
        var fields=new TreeSet<String>();
        current.fieldNames().forEachRemaining(fields::add);
        if (previous!=null) previous.fieldNames().forEachRemaining(fields::add);
        fields.remove("activityTitle");
        return fields.stream().filter(name->previous==null||!Objects.equals(previous.path(name),current.path(name))).toList();
    }
}


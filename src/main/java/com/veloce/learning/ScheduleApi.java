package com.veloce.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloce.common.*;
import com.veloce.integration.ReminderRules;
import com.veloce.workspace.WorkspaceStore;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class ScheduleApi {
    public record Session(UUID id,UUID workspaceId,String workspaceName,String date,Instant start,Instant end,
                          int minutes,String kind,String title,String activityKind,String outcome,JsonNode resources,
                          String status,String zone,UUID runningFocusId,Instant focusStartedAt,String focusBlockedReason,String trackLabel) {}
    public record Range(LocalDate from,LocalDate to) {
        public static Range of(LocalDate from,LocalDate to,int max) {
            if (from==null || to==null || !from.isBefore(to) || to.isAfter(from.plusDays(max))
                || from.getYear()<1900 || to.getYear()>2200) throw Problem.invalid("Choose a valid date range of up to "+max+" days.");
            return new Range(from,to);
        }
    }
    private static final String SELECT="""
        SELECT s.*,w.name,w.status AS workspace_status,w.active_plan_id,n.title,n.outcome,n.kind AS activity_kind,n.resources,
          p.settings->'preparation'->>'zone' AS zone, c.completed_at,f.id AS focus_id,f.started_at AS focus_started,
          EXISTS(SELECT 1 FROM study_presence a WHERE a.workspace_id=s.workspace_id AND a.study_date=s.study_date
            AND a.ended_at IS NULL) AS checked_in,
          EXISTS(SELECT 1 FROM study_presence a WHERE a.workspace_id=s.workspace_id AND a.study_date=s.study_date
            AND a.ended_at IS NULL AND a.break_started_at IS NOT NULL) AS on_break
        FROM schedule_slots s JOIN workspaces w ON w.id=s.workspace_id JOIN plan_versions p ON p.id=s.plan_id
        LEFT JOIN roadmap_nodes n ON n.workspace_id=s.workspace_id AND n.roadmap_id=s.roadmap_id AND n.node_id=s.node_id
        LEFT JOIN session_completions c ON c.session_id=s.id
        LEFT JOIN focus_sessions f ON f.session_id=s.id AND f.ended_at IS NULL
        """;
    private final WorkspaceStore store;
    private final Clock clock;
    ScheduleApi(WorkspaceStore store,Clock clock) { this.store=store; this.clock=clock; }

    @GetMapping("/workspaces/{w}/schedule")
    List<Session> schedule(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,
                           @RequestParam LocalDate from,@RequestParam LocalDate to,@RequestParam(defaultValue="0") int page) {
        store.owned(owner,w); Range.of(from,to,62); page(page);
        return store.db.query(SELECT+"""
            WHERE w.owner_id=? AND w.id=? AND s.study_date>=? AND s.study_date<?
              AND (s.plan_id=w.active_plan_id OR c.session_id IS NOT NULL OR s.cancelled OR s.starts_at<now())
            ORDER BY s.starts_at,s.id LIMIT 120 OFFSET ?
            """,this::map,owner,w,from,to,page*120);
    }
    @GetMapping("/agenda")
    List<Session> agenda(@AuthenticationPrincipal UUID owner,@RequestParam LocalDate from,@RequestParam LocalDate to,
                         @RequestParam(defaultValue="UTC") String zone,@RequestParam(defaultValue="0") int page) {
        Range.of(from,to,31); page(page);
        ZoneId tz; try { tz=ZoneId.of(zone); } catch (DateTimeException e) { throw Problem.invalid("Choose a valid timezone."); }
        return store.db.query(SELECT+"""
            WHERE w.owner_id=? AND w.status='ACTIVE' AND s.plan_id=w.active_plan_id AND NOT s.cancelled
              AND s.starts_at<? AND s.ends_at>?
            ORDER BY s.starts_at,s.id LIMIT 120 OFFSET ?
            """,this::map,owner,to.atStartOfDay(tz).toOffsetDateTime(),from.atStartOfDay(tz).toOffsetDateTime(),page*120);
    }

    @GetMapping("/workspaces/{w}/progress")
    Map<String,Object> progress(@AuthenticationPrincipal UUID owner,@PathVariable UUID w) {
        var workspace=store.owned(owner,w);
        if (workspace.activePlanId()==null) return Map.of();
        var result=new LinkedHashMap<String,Object>(store.db.queryForMap("""
            SELECT (p.summary->>'effort')::integer AS "plannedMinutes",p.summary->>'completionDate' AS "completionDate",
              p.summary->>'finalDate' AS "finalDate",p.settings->'preparation'->>'zone' AS "zone",
              (SELECT coalesce(sum(s.minutes),0) FROM schedule_slots s JOIN session_completions c ON c.session_id=s.id
                WHERE s.workspace_id=p.workspace_id AND s.roadmap_id=p.roadmap_id) AS "completedMinutes",
              (SELECT coalesce(floor(sum(extract(epoch FROM (f.ended_at-f.started_at)))),0)::bigint FROM focus_sessions f
                WHERE f.workspace_id=p.workspace_id AND f.ended_at IS NOT NULL) AS "focusSeconds",
              (SELECT count(*) FROM schedule_slots s WHERE s.plan_id=p.id AND s.kind='STUDY' AND NOT s.cancelled
                AND s.ends_at BETWEEN now()-interval '30 days' AND now()) AS "dueSessions",
              (SELECT count(*) FROM schedule_slots s JOIN session_completions c ON c.session_id=s.id
                WHERE s.plan_id=p.id AND NOT s.cancelled AND s.ends_at BETWEEN now()-interval '30 days' AND now()) AS "completedDueSessions",
              p.settings AS "settings",p.roadmap_id AS "roadmapId"
              FROM plan_versions p WHERE p.id=?
            """,workspace.activePlanId()));
        result.put("settings",store.json.read(result.get("settings").toString()));
        return result;
    }

    @GetMapping("/workspaces/{w}/upcoming")
    List<Session> upcoming(@AuthenticationPrincipal UUID owner,@PathVariable UUID w) {
        store.owned(owner,w);
        return store.db.query(SELECT+"""
            WHERE w.owner_id=? AND w.id=? AND w.status='ACTIVE' AND s.plan_id=w.active_plan_id AND NOT s.cancelled
              AND s.kind='STUDY' AND s.ends_at>now() AND c.session_id IS NULL ORDER BY s.starts_at,s.id LIMIT 5
            """,this::map,owner,w);
    }

    @GetMapping("/workspaces/{w}/sessions/{s}")
    Session session(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable UUID s) {
        return one(owner,w,s);
    }

    @PostMapping("/workspaces/{w}/sessions/{s}/completion")
    @Transactional
    Session complete(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable UUID s) {
        store.accountRevision(owner,true);
        var workspace=store.owned(owner,w,true);
        var item=one(owner,w,s);
        actionable(workspace,item);
        if (!item.status().equals("Completed")) {
            store.db.update("INSERT INTO session_completions(session_id,workspace_id) VALUES(?,?) ON CONFLICT DO NOTHING",s,w);
            store.changed(w);
            store.integrationChanged(owner,w,"completion-changed");
        }
        return one(owner,w,s);
    }
    @DeleteMapping("/workspaces/{w}/sessions/{s}/completion")
    @Transactional
    Session undo(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable UUID s) {
        store.accountRevision(owner,true);
        var workspace=store.owned(owner,w,true);
        var item=one(owner,w,s);
        actionable(workspace,item);
        if (item.status().equals("Completed")) {
            boolean activeSlot=store.db.queryForObject("SELECT plan_id=? FROM schedule_slots WHERE id=?",Boolean.class,workspace.activePlanId(),s);
            if (!activeSlot) throw Problem.conflict("This completion was preserved when the plan was revised and can no longer be undone.");
            store.db.update("DELETE FROM session_completions WHERE session_id=? AND workspace_id=?",s,w);
            store.changed(w);
            store.integrationChanged(owner,w,"completion-changed");
        }
        return one(owner,w,s);
    }
    @PostMapping("/workspaces/{w}/sessions/{s}/focus")
    @Transactional
    Session focus(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable UUID s) {
        store.accountRevision(owner,true);
        var workspace=store.owned(owner,w,true);
        var item=one(owner,w,s); actionable(workspace,item);
        if (item.runningFocusId()!=null) return item;
        if (!workspace.status().equals("ACTIVE")) throw Problem.conflict("Resume the workspace before starting a focus session.");
        if (item.focusBlockedReason()!=null) throw Problem.conflict(item.focusBlockedReason());
        if (store.db.update("""
            INSERT INTO focus_sessions(id,owner_id,workspace_id,session_id) VALUES(?,?,?,?)
            ON CONFLICT(owner_id) WHERE ended_at IS NULL DO NOTHING
            """,UUID.randomUUID(),owner,w,s)==0) throw Problem.conflict("Stop your current focus timer before starting another.");
        return one(owner,w,s);
    }
    @PostMapping("/workspaces/{w}/sessions/{s}/focus/{f}/stop")
    @Transactional
    Session stop(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable UUID s,@PathVariable UUID f) {
        store.owned(owner,w); one(owner,w,s);
        if (store.db.update("""
            UPDATE focus_sessions SET ended_at=coalesce(ended_at,now())
            WHERE owner_id=? AND workspace_id=? AND session_id=? AND id=?
            """,owner,w,s,f)==0) throw Problem.missing();
        return one(owner,w,s);
    }

    @GetMapping(value="/workspaces/{w}/calendar.ics",produces="text/calendar")
    ResponseEntity<byte[]> export(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,
                                   @RequestParam LocalDate from,@RequestParam LocalDate to) {
        var workspace=store.owned(owner,w); Range.of(from,to,732);
        if (workspace.activePlanId()==null) throw Problem.conflict("Generate a plan before exporting.");
        StringBuilder ics=new StringBuilder();
        for (String line:List.of("BEGIN:VCALENDAR","VERSION:2.0","PRODID:-//Veloce//Study Schedule//EN","CALSCALE:GREGORIAN")) line(ics,line);
        var ruleRows=store.db.query("SELECT rules FROM integration_preferences WHERE workspace_id=?",(r,n)->r.getString(1),w);
        var rules=ReminderRules.from(ruleRows.isEmpty()?com.fasterxml.jackson.databind.node.MissingNode.getInstance():store.json.read(ruleRows.getFirst()),store.json);
        DateTimeFormatter utc=DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
        String stamp=utc.format(clock.instant());
        var last=new LocalDate[]{null};
        store.db.query(SELECT+"""
            WHERE w.owner_id=? AND w.id=? AND s.plan_id=w.active_plan_id AND NOT s.cancelled
              AND s.kind='STUDY' AND s.study_date>=? AND s.study_date<? ORDER BY s.starts_at,s.id
            """,rs->{
                var session=map(rs,0);
                LocalDate date=LocalDate.parse(session.date());
                boolean first=!date.equals(last[0]); last[0]=date;
                line(ics,"BEGIN:VEVENT"); line(ics,"UID:"+session.id()+"@veloce.local"); line(ics,"DTSTAMP:"+stamp);
                line(ics,"DTSTART:"+utc.format(session.start())); line(ics,"DTEND:"+utc.format(session.end()));
                String label=session.trackLabel()==null?session.title():session.trackLabel()+" · "+session.title();
                line(ics,"SUMMARY:"+escape(rules.genericTitles()?"Veloce study":"Veloce · "+label));
                if (!rules.genericTitles()) line(ics,"DESCRIPTION:"+escape(session.workspaceName()+" — "+session.outcome()));
                if (Set.of("CALENDAR","BOTH").contains(rules.channel()) && !session.status().equals("Completed")) {
                    for (int offset:rules.offsets(first,session.activityKind(),session.start(),ZoneId.of(session.zone()))) {
                        line(ics,"BEGIN:VALARM"); line(ics,"TRIGGER:"+(offset==0?"PT0M":"-PT"+offset+"M"));
                        line(ics,"ACTION:DISPLAY"); line(ics,"DESCRIPTION:Veloce study"); line(ics,"END:VALARM");
                    }
                }
                line(ics,"END:VEVENT");
                if (first) for (var reminder:rules.calendarReminders(w,date,session.start(),ZoneId.of(session.zone()))) {
                    line(ics,"BEGIN:VEVENT"); line(ics,"UID:"+reminder.id()+"@veloce.local"); line(ics,"DTSTAMP:"+stamp);
                    line(ics,"DTSTART:"+utc.format(reminder.at())); line(ics,"DTEND:"+utc.format(reminder.at().plusSeconds(60)));
                    line(ics,"SUMMARY:"+escape(reminder.title())); line(ics,"TRANSP:TRANSPARENT");
                    line(ics,"BEGIN:VALARM"); line(ics,"TRIGGER:PT0M"); line(ics,"ACTION:DISPLAY");
                    line(ics,"DESCRIPTION:"+escape(reminder.title())); line(ics,"END:VALARM"); line(ics,"END:VEVENT");
                }
            },owner,w,from,to);
        line(ics,"END:VCALENDAR");
        return ResponseEntity.ok().header("Content-Disposition","attachment; filename=\"veloce-"+w+".ics\"")
            .cacheControl(CacheControl.noStore()).contentType(new MediaType("text","calendar",StandardCharsets.UTF_8))
            .body(ics.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String value) {
        return value.replace("\\","\\\\").replace("\r\n","\n").replace("\r","\n").replace("\n","\\n")
            .replace(",","\\,").replace(";","\\;");
    }
    static void line(StringBuilder out,String line) {
        int bytes=0;
        for (int i=0;i<line.length();) {
            int cp=line.codePointAt(i); i+=Character.charCount(cp);
            int n=cp<128?1:cp<2048?2:cp<65536?3:4;
            if (bytes+n>75) { out.append("\r\n "); bytes=1; }
            out.appendCodePoint(cp); bytes+=n;
        }
        out.append("\r\n");
    }

    private Session one(UUID owner,UUID w,UUID session) {
        return store.db.query(SELECT+" WHERE w.owner_id=? AND w.id=? AND s.id=?",this::map,owner,w,session)
            .stream().findFirst().orElseThrow(Problem::missing);
    }
    private Session map(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        Instant start=r.getObject("starts_at",OffsetDateTime.class).toInstant(),end=r.getObject("ends_at",OffsetDateTime.class).toInstant();
        String kind=r.getString("kind");
        boolean superseded=!Objects.equals(r.getObject("active_plan_id",UUID.class),r.getObject("plan_id",UUID.class));
        String status=r.getObject("completed_at")!=null?"Completed":r.getBoolean("cancelled")||superseded?"Rescheduled":kind.equals("BREAK")?"Break":
            r.getString("workspace_status").equals("ARCHIVED")?"Archived":!r.getString("workspace_status").equals("ACTIVE")?"Paused":end.isBefore(clock.instant())?"Overdue":"Planned";
        var focus=r.getObject("focus_started",OffsetDateTime.class);
        String zone=Objects.requireNonNullElse(r.getString("zone"),"UTC");
        String blocked=!r.getDate("study_date").toLocalDate().equals(LocalDate.now(clock.withZone(ZoneId.of(zone))))
            ?"Choose today's study schedule to start a focus timer."
            :!r.getBoolean("checked_in")?"Check in from Today before starting a focus timer."
            :r.getBoolean("on_break")?"End your study break before starting a focus timer."
            :start.isAfter(clock.instant())?"This study block has not started yet. Choose the current block in Today's daily rhythm.":null;
        return new Session(r.getObject("id",UUID.class),r.getObject("workspace_id",UUID.class),r.getString("name"),
            r.getDate("study_date").toLocalDate().toString(),start,end,r.getInt("minutes"),kind,
            kind.equals("BREAK")?"Break":r.getString("title"),r.getString("activity_kind"),r.getString("outcome"),
            store.json.read(r.getString("resources")),status,zone,r.getObject("focus_id",UUID.class),
            focus==null?null:focus.toInstant(),blocked,r.getString("track_label"));
    }
    private void actionable(WorkspaceStore.Workspace w,Session item) {
        if (w.status().equals("ARCHIVED") || !item.kind().equals("STUDY") || item.status().equals("Rescheduled"))
            throw Problem.conflict("This session can no longer be changed.");
    }
    private void page(int page) { if (page<0 || page>1000) throw Problem.invalid("Choose a valid schedule page."); }
}

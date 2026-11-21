package com.veloce.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloce.common.*;
import java.time.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspaces/{w}/study")
public class StudyPresenceApi {
    private final StudyStore s;
    private final StudyPreferences prefs;
    private final StudyCoach coach;
    private final Limits limits;
    private final StudyLifecycle lifecycle;
    private final StudyDaySchedule schedule;
    StudyPresenceApi(StudyStore s,StudyPreferences prefs,StudyCoach coach,Limits limits,StudyLifecycle lifecycle,StudyDaySchedule schedule) { this.s=s; this.prefs=prefs;this.coach=coach;this.limits=limits;this.lifecycle=lifecycle;this.schedule=schedule; }

    @PostMapping("/provider-test")
    Object test(@AuthenticationPrincipal UUID owner,@PathVariable UUID w) {
        s.ws.owned(owner,w);limits.check("provider-test:"+owner,5,60);
        var settings=prefs.get(w);
        var context=s.json.object().put("topic","Explain one effective learning technique.");
        coach.turn(w,settings.path("provider").asText(),settings.path("model").asText(),context,"ORAL");
        return Map.of("connected",true);
    }

    @GetMapping("/preferences")
    Object preferences(@AuthenticationPrincipal UUID owner,@PathVariable UUID w) { s.ws.owned(owner,w); return prefs.get(w); }
    @PutMapping("/preferences")
    Object preferences(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@RequestBody JsonNode input) { return prefs.save(owner,w,input); }
    @DeleteMapping("/preferences/keys/{provider}")
    Object forget(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable String provider,@RequestBody JsonNode input) {
        return prefs.forget(owner,w,provider,StudyRules.version(input));
    }

    private List<Map<String,Object>> presence(UUID w,LocalDate date) {
        return s.ws.db.query("""
            SELECT id,study_date,started_at,ended_at,break_started_at,break_seconds,end_reason
            FROM study_presence WHERE workspace_id=? AND (study_date=? OR ended_at IS NULL) ORDER BY started_at DESC LIMIT 30
            """,(r,n)->{
                var row=new LinkedHashMap<String,Object>();
                Instant start=r.getObject(3,OffsetDateTime.class).toInstant();
                var ended=r.getObject(4,OffsetDateTime.class);
                var paused=r.getObject(5,OffsetDateTime.class);
                long elapsed=Math.max(0,Duration.between(start,ended==null?s.clock.instant():ended.toInstant()).getSeconds());
                long breaks=r.getLong(6)+(paused==null?0:Math.max(0,Duration.between(paused.toInstant(),s.clock.instant()).getSeconds()));
                row.put("id",r.getObject(1)); row.put("date",r.getObject(2,LocalDate.class));
                row.put("start",start); row.put("end",ended==null?null:ended.toInstant());
                row.put("breakStart",paused==null?null:paused.toInstant());
                row.put("elapsedSeconds",elapsed); row.put("breakSeconds",Math.min(breaks,elapsed));
                row.put("studySeconds",Math.max(0,elapsed-breaks)); row.put("endReason",r.getString(7)); return (Map<String,Object>)row;
            },w,date);
    }
    @GetMapping("/presence")
    Object presence(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@RequestParam String date) {
        s.ws.owned(owner,w); return presence(w,StudyRules.date(date));
    }
    @PostMapping("/presence/{action}")
    @Transactional
    Object action(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable String action,@RequestBody(required=false) JsonNode input) {
        s.ws.accountRevision(owner,true);
        var workspace=s.ws.owned(owner,w,true); s.writable(workspace); s.roadmap(workspace);
        var open=s.ws.db.query("SELECT id,workspace_id,break_started_at,study_date FROM study_presence WHERE owner_id=? AND ended_at IS NULL FOR UPDATE",
            (r,n)->{var row=new HashMap<String,Object>();row.put("id",r.getObject(1,UUID.class));row.put("workspace",r.getObject(2,UUID.class));row.put("break",r.getObject(3,OffsetDateTime.class));row.put("date",r.getObject(4,LocalDate.class));return row;},owner);
        var current=open.isEmpty()?null:open.getFirst();
        if (action.equals("start")) {
            if (!workspace.status().equals("ACTIVE")) throw Problem.conflict("Resume this workspace before starting a study day.");
            if (current!=null&&!current.get("workspace").equals(w)) throw Problem.conflict("Finish the study day in your other workspace first.");
            if (current!=null&&!current.get("date").equals(s.today(workspace)))
                throw Problem.conflict("Your previous study day is still open. Use Finish study day below before checking in today.");
            if (current==null) {
                var today=s.today(workspace);
                var now=s.clock.instant();
                schedule.start(workspace,today,now);
                s.ws.db.update("INSERT INTO study_presence(id,owner_id,workspace_id,study_date,started_at) VALUES(?,?,?,?,?)",
                    UUID.randomUUID(),owner,w,today,now.atOffset(ZoneOffset.UTC));
            }
        } else {
            if (current==null||!current.get("workspace").equals(w)) throw Problem.conflict("Start this workspace's study day first.");
            UUID id=(UUID)current.get("id");
            if (action.equals("break")) {
                if (current.get("break")==null) {
                    s.ws.db.update("UPDATE study_presence SET break_started_at=now() WHERE id=?",id);
                    lifecycle.pauseTimers(workspace);
                }
            } else if (action.equals("resume")) {
                if (current.get("break")!=null) s.ws.db.update("UPDATE study_presence SET break_seconds=break_seconds+greatest(0,extract(epoch FROM(now()-break_started_at)))::bigint,break_started_at=NULL WHERE id=?",id);
            } else if (action.equals("finish")) {
                String reason=input==null?"":Json.text(input,"reason",1000,false);
                s.ws.db.update("""
                    UPDATE study_presence SET ended_at=now(),end_reason=?,break_seconds=break_seconds+
                      CASE WHEN break_started_at IS NULL THEN 0 ELSE greatest(0,extract(epoch FROM(now()-break_started_at)))::bigint END,
                      break_started_at=NULL WHERE id=?
                    """,reason,id);
                lifecycle.pause(workspace,"study-day-finished");
            } else throw Problem.invalid("Choose a study-day action.");
        }
        return presence(w,s.today(workspace));
    }
}

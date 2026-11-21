package com.veloce.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloce.common.*;
import com.veloce.workspace.WorkspaceStore.Workspace;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Workspace-scoped version of Chronis carryover, using this workspace's study windows. */
final class StudyCarryover {
    static final Set<String> CAUSES=Set.of("TASK_DELAY","LATE_LOGIN","EARLY_LOGOUT","LATE_LOGOUT","LEAVE","PRACTICE");
    private final StudyStore s;
    StudyCarryover(StudyStore s){this.s=s;}
    record Slot(String node,Instant from,Instant to,int minutes,boolean completed){}
    List<Slot> slots(Workspace w,LocalDate date) {
        return s.ws.db.query("""
            SELECT x.node_id,x.starts_at,x.ends_at,x.minutes,EXISTS(SELECT 1 FROM session_completions c WHERE c.session_id=x.id)
            FROM schedule_slots x WHERE x.workspace_id=? AND x.plan_id=? AND x.study_date=? AND x.kind='STUDY' AND NOT x.cancelled
            ORDER BY x.starts_at,x.id
            """,(r,n)->new Slot(r.getString(1),r.getObject(2,OffsetDateTime.class).toInstant(),r.getObject(3,OffsetDateTime.class).toInstant(),r.getInt(4),r.getBoolean(5)),w.id(),w.activePlanId(),date);
    }
    List<StudyStore.Record> leaves(Workspace w,LocalDate date) {
        return s.list(w.id(),s.roadmap(w),"LEAVE",date,0);
    }
    static int capacity(List<Slot> slots,Instant from,Instant to,List<Instant[]> unavailable) {
        long nanos=0;
        for(var slot:slots) {
            Instant left=slot.from().isAfter(from)?slot.from():from,right=slot.to().isBefore(to)?slot.to():to;
            if(!left.isBefore(right))continue;
            var blocks=unavailable.stream().filter(b->b[0].isBefore(right)&&b[1].isAfter(left))
                .sorted(Comparator.comparing(b->b[0])).toList();
            Instant cursor=left;
            for(var block:blocks) {
                if(block[0].isAfter(cursor))nanos+=Duration.between(cursor,block[0]).toNanos();
                if(block[1].isAfter(cursor))cursor=block[1].isBefore(right)?block[1]:right;
            }
            if(cursor.isBefore(right))nanos+=Duration.between(cursor,right).toNanos();
        }
        return (int)(nanos/Duration.ofMinutes(1).toNanos());
    }
    List<Map<String,Object>> unfinished(Workspace w,LocalDate date) {
        var planned=new LinkedHashMap<String,Integer>();
        for(var slot:slots(w,date))planned.merge(slot.node(),slot.minutes(),Integer::sum);
        var saved=new HashMap<String,StudyStore.Record>();s.day(w.id(),s.roadmap(w),date).forEach(r->saved.put(r.nodeId(),r));
        var result=new ArrayList<Map<String,Object>>();
        for(var item:planned.entrySet()) {
            var activity=s.node(w.id(),s.roadmap(w),item.getKey());var row=saved.get(item.getKey());
            JsonNode work=row==null?s.json.object():row.data();
            if(Set.of("DONE","SKIPPED").contains(work.path("status").asText()))continue;
            var cards=DailySections.cards(activity,item.getValue());
            for(var card:cards) {
                String section=Objects.toString(card.get("reportSection"),"");
                var data=section.isEmpty()?work:work.has("dailySections")?work.path("dailySections").path(section):section.equals("CONCEPT")?work:s.json.object();
                int plannedMinutes=cards.size()==1?item.getValue():((Number)card.get("minutes")).intValue();
                if(plannedMinutes==0)continue;
                if(Set.of("DONE","SKIPPED").contains(data.path("status").asText()))continue;
                var candidate=new LinkedHashMap<String,Object>();candidate.put("nodeId",item.getKey());candidate.put("section",section);
                candidate.put("title",activity.get("title")+(section.isEmpty()?"":" · "+DailySections.TITLES.get(section)));
                candidate.put("estimatedMinutes",Math.max(1,plannedMinutes-data.path("actualMinutes").asInt()));
                candidate.put("notes",data.path("nextSteps").asText(""));result.add(candidate);
            }
        }
        return result;
    }
    Map<String,Object> plan(Workspace w,LocalDate date) {
        var slots=slots(w,date);var blocked=new ArrayList<Instant[]>();var zone=s.zone(w);
        for(var row:leaves(w,date))if(row.data().path("status").asText().equals("PLANNED"))blocked.add(new Instant[]{
            date.atTime(LocalTime.parse(row.data().path("from").asText())).atZone(zone).toInstant(),
            date.atTime(LocalTime.parse(row.data().path("to").asText())).atZone(zone).toInstant()});
        Instant first=slots.isEmpty()?date.atStartOfDay(zone).toInstant():slots.getFirst().from(),
            last=slots.isEmpty()?first:slots.getLast().to();
        int planned=slots.stream().mapToInt(Slot::minutes).sum(),leave=planned-capacity(slots,first,last,blocked);
        var attendance=s.ws.db.query("SELECT started_at,ended_at FROM study_presence WHERE workspace_id=? AND study_date=? ORDER BY started_at",
            (r,n)->new Instant[]{r.getObject(1,OffsetDateTime.class).toInstant(),r.getObject(2,OffsetDateTime.class)==null?null:r.getObject(2,OffsetDateTime.class).toInstant()},w.id(),date);
        Instant checkIn=attendance.isEmpty()?null:attendance.getFirst()[0],checkOut=attendance.isEmpty()?null:attendance.getLast()[1];
        if(checkIn!=null&&checkIn.isAfter(first))blocked.add(new Instant[]{first,checkIn});
        if(checkOut!=null&&checkOut.isBefore(last))blocked.add(new Instant[]{checkOut,last});
        int remaining=capacity(slots,s.clock.instant().isAfter(first)?s.clock.instant():first,last,blocked);
        int open=s.ws.db.queryForObject("SELECT coalesce(sum((data->>'estimatedMinutes')::int),0) FROM study_records WHERE workspace_id=? AND roadmap_id=? AND record_type='CARRY' AND data->>'status'='OPEN'",Integer.class,w.id(),s.roadmap(w));
        int due=s.ws.db.queryForObject("SELECT coalesce(sum((data->>'estimatedMinutes')::int),0) FROM study_records WHERE workspace_id=? AND roadmap_id=? AND record_type='CARRY' AND data->>'status'='OPEN' AND data->>'practiceId' IS NULL AND study_date<=?",Integer.class,w.id(),s.roadmap(w),date);
        var moved=new HashSet<>(s.ws.db.queryForList("SELECT node_id||'/'||coalesce(data->>'section','') FROM study_records WHERE workspace_id=? AND roadmap_id=? AND record_type='CARRY' AND data->>'status'='OPEN' AND data->>'sourceDate'=? AND study_date>?",String.class,w.id(),s.roadmap(w),date.toString(),date));
        int outstanding=unfinished(w,date).stream().filter(c->!moved.contains(c.get("nodeId")+"/"+c.get("section"))).mapToInt(c->((Number)c.get("estimatedMinutes")).intValue()).sum();
        var result=new LinkedHashMap<String,Object>();result.put("plannedFocusMinutes",planned);result.put("availableFocusMinutes",capacity(slots,first,last,blocked));
        result.put("remainingFocusMinutes",remaining);result.put("leaveFocusMinutes",leave);result.put("openCarryoverMinutes",open);result.put("dueCarryoverMinutes",due);
        result.put("estimatedUnfinishedMinutes",Math.max(0,outstanding+due-remaining));result.put("estimatedLogoutAt",slots.isEmpty()?"":last.toString());
        result.put("estimatedStartAt",slots.isEmpty()?"":first.toString());
        result.put("lateLoginMinutes",checkIn==null?0:Math.max(0,Duration.between(first,checkIn).toMinutes()));
        result.put("earlyLogoutMinutes",checkOut==null?0:Math.max(0,Duration.between(checkOut,last).toMinutes()));
        result.put("lateLogoutMinutes",checkOut==null?0:Math.max(0,Duration.between(last,checkOut).toMinutes()));
        return result;
    }
    StudyStore.Record save(Workspace w,String id,JsonNode input) {
        var roadmap=s.roadmap(w);var data=input.path("data");var existing=s.get(w.id(),roadmap,"CARRY",id);
        String node=Json.text(data,"nodeId",80,true);var activity=s.node(w.id(),roadmap,node);
        var source=StudyRules.date(Json.text(data,"sourceDate",10,true));var target=StudyRules.date(Json.text(data,"targetDate",10,true));
        if(source.isBefore(s.historyStart(w).date())||!target.isAfter(source))throw Problem.invalid("Choose a source within preparation and a later target date.");
        String status=Json.text(data,"status",15,true),cause=Json.text(data,"cause",20,false),section=Json.text(data,"section",20,false);
        if(cause.isBlank())cause=existing.map(r->r.data().path("cause").asText("TASK_DELAY")).orElse("TASK_DELAY");
        if(!Set.of("OPEN","DONE","CANCELLED").contains(status)||!CAUSES.contains(cause)||!section.isBlank()&&!DailySections.TITLES.containsKey(section))
            throw Problem.invalid("Choose a valid carryover status, reason and work section.");
        var clean=s.json.object().put("nodeId",node).put("title",Json.text(data,"title",600,false))
            .put("sourceDate",source.toString()).put("targetDate",target.toString()).put("status",status).put("cause",cause).put("section",section)
            .put("estimatedMinutes",StudyRules.number(data,"estimatedMinutes",1,43200,1))
            .put("reason",Json.text(data,"reason",1000,false)).put("notes",Json.text(data,"notes",4000,false));
        if(clean.path("title").asText().isBlank())clean.put("title",activity.get("title").toString());
        if(existing.isPresent()&&existing.get().data().hasNonNull("practiceId")) {
            String practice=existing.get().data().path("practiceId").asText();clean.put("practiceId",practice).put("cause","PRACTICE");
            if(status.equals("DONE")&&!s.get(w.id(),roadmap,"PRACTICE",practice).map(r->r.data().path("status").asText().equals("COMPLETED")).orElse(false))
                throw Problem.conflict("Complete the linked practice session before resolving its carryover.");
        }
        return s.save(w.id(),roadmap,"CARRY",id,node,target,clean,StudyRules.version(input),StudyRules.mutation(input));
    }
    static String bulkKey(UUID roadmap,LocalDate source,String node,String section) {
        return UUID.nameUUIDFromBytes((roadmap+"/"+source+"/"+node+"/"+section).getBytes(StandardCharsets.UTF_8)).toString();
    }
}

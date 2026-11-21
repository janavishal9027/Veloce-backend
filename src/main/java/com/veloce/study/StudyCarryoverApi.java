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
public class StudyCarryoverApi {
    private final StudyStore s;
    StudyCarryoverApi(StudyStore s){this.s=s;}
    @GetMapping("/carryover")
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    Map<String,Object> workspace(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@RequestParam String date,@RequestParam(defaultValue="0") int page) {
        var workspace=s.ws.owned(owner,w);var selected=StudyRules.date(date);var carry=new StudyCarryover(s);
        return Map.of("plan",carry.plan(workspace,selected),"carryovers",s.list(w,s.roadmap(workspace),"CARRY",null,page),
            "leaves",carry.leaves(workspace,selected),"unfinished",carry.unfinished(workspace,selected));
    }
    @PostMapping("/carryover/unfinished")
    @Transactional
    Map<String,Object> unfinished(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@RequestBody JsonNode input) {
        var workspace=s.ws.owned(owner,w,true);s.writable(workspace);var roadmap=s.roadmap(workspace);var carry=new StudyCarryover(s);
        var source=StudyRules.date(Json.text(input,"sourceDate",10,true));var target=StudyRules.date(Json.text(input,"targetDate",10,true));
        String cause=Json.text(input,"cause",20,true);int count=0;
        if(!target.isAfter(source)||source.isAfter(s.today(workspace))||source.isBefore(s.historyStart(workspace).date())||!StudyCarryover.CAUSES.contains(cause)||cause.equals("PRACTICE"))
            throw Problem.invalid("Choose an existing study day, a later target and a carryover reason.");
        for(var task:carry.unfinished(workspace,source)) {
            String node=task.get("nodeId").toString(),section=task.get("section").toString(),id=StudyCarryover.bulkKey(roadmap,source,node,section);
            var old=s.get(w,roadmap,"CARRY",id);
            if(old.isPresent()&&old.get().data().path("status").asText().equals("DONE"))continue;
            var data=s.json.object().put("nodeId",node).put("sourceDate",source.toString()).put("targetDate",target.toString())
                .put("cause",cause).put("section",section).put("status","OPEN").put("title",task.get("title").toString())
                .put("estimatedMinutes",((Number)task.get("estimatedMinutes")).intValue()).put("reason","Carry unfinished work forward").put("notes",task.get("notes").toString());
            if(old.isPresent()&&old.get().data().equals(data))continue;
            var request=s.json.object().put("expectedVersion",old.map(StudyStore.Record::version).orElse(0L)).put("mutationId",UUID.randomUUID().toString());request.set("data",data);
            carry.save(workspace,id,request);count++;
        }
        return Map.of("carried",count);
    }
    @PutMapping("/leaves/{id}")
    @Transactional
    StudyStore.Record leave(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable UUID id,@RequestBody JsonNode input) {
        var workspace=s.ws.owned(owner,w,true);s.writable(workspace);var roadmap=s.roadmap(workspace);var data=input.path("data");
        var date=StudyRules.date(Json.text(data,"date",10,true));String reason=Json.text(data,"reason",1000,true),status=Json.text(data,"status",15,true);
        LocalTime from,to;try{from=LocalTime.parse(Json.text(data,"from",5,true));to=LocalTime.parse(Json.text(data,"to",5,true));}catch(DateTimeException e){throw Problem.invalid("Choose valid study leave times.");}
        if(reason.length()<10||!from.isBefore(to)||!Set.of("PLANNED","CANCELLED").contains(status)||date.isBefore(s.historyStart(workspace).date()))
            throw Problem.invalid("Explain the leave in at least 10 characters and choose a valid study interval.");
        var carry=new StudyCarryover(s);var zone=s.zone(workspace);Instant begin=date.atTime(from).atZone(zone).toInstant(),end=date.atTime(to).atZone(zone).toInstant();
        var slots=carry.slots(workspace,date);
        if(status.equals("PLANNED")) {
            if(slots.isEmpty()||begin.isBefore(slots.getFirst().from())||end.isAfter(slots.getLast().to()))throw Problem.invalid("Keep leave within this day's scheduled study hours.");
            for(var row:carry.leaves(workspace,date))if(!row.key().equals(id.toString())&&row.data().path("status").asText().equals("PLANNED")) {
                var a=LocalTime.parse(row.data().path("from").asText());var b=LocalTime.parse(row.data().path("to").asText());
                if(from.isBefore(b)&&to.isAfter(a))throw Problem.conflict("This study leave overlaps an existing leave interval.");
            }
        }
        var clean=s.json.object().put("date",date.toString()).put("from",from.toString()).put("to",to.toString()).put("reason",reason).put("status",status);
        return s.save(w,roadmap,"LEAVE",id.toString(),null,date,clean,StudyRules.version(input),StudyRules.mutation(input));
    }
}

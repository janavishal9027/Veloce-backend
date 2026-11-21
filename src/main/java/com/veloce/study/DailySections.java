package com.veloce.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloce.common.*;
import java.util.*;

/** Chronis's six work domains, using tasks from the imported roadmap. */
final class DailySections {
    static final Map<String,String> TITLES=new LinkedHashMap<>();
    static {
        TITLES.put("CONCEPT","Concepts & understanding");
        TITLES.put("BASIC_CODING","Basic coding & tests");
        TITLES.put("SCENARIO","Scenario-based questions");
        TITLES.put("PROJECT","Project work");
        TITLES.put("INTERVIEW","Interview practice");
        TITLES.put("REVIEW","Review & next steps");
    }
    private static final Map<String,String> LABELS=Map.ofEntries(
        Map.entry("Concepts","CONCEPT"),Map.entry("Theory deliverable","CONCEPT"),
        Map.entry("Coding practice","BASIC_CODING"),Map.entry("Scenario and edge cases","SCENARIO"),
        Map.entry("Project workstream","PROJECT"),Map.entry("Project sprint","PROJECT"),
        Map.entry("Project deliverable","PROJECT"),Map.entry("Interview prompt","INTERVIEW"),
        Map.entry("Review","REVIEW"),Map.entry("Next steps","REVIEW"));
    static Map<String,String> tasks(String outcome) {
        var parts=new LinkedHashMap<String,StringBuilder>();String current=null;
        for(String line:outcome.split("\\R")) {
            if(LABELS.containsKey(line.trim())) {
                current=LABELS.get(line.trim());
                parts.computeIfAbsent(current,x->new StringBuilder()).append(line.trim()).append('\n');
            } else if(current!=null) parts.get(current).append(line).append('\n');
        }
        var result=new LinkedHashMap<String,String>();
        parts.forEach((k,v)->result.put(k,v.toString().trim()));
        return result;
    }
    static boolean structured(Map<String,Object> activity) {
        return tasks(Objects.toString(activity.get("outcome"),"")).size()>1;
    }
    static List<Map<String,Object>> cards(Map<String,Object> activity) {
        return cards(activity,((Number)activity.getOrDefault("minutes",0)).intValue());
    }
    static List<Map<String,Object>> cards(Map<String,Object> activity,int budget) {
        if(!structured(activity)) return List.of(activity);
        var tasks=tasks(Objects.toString(activity.get("outcome"),""));
        var result=new ArrayList<Map<String,Object>>();
        int[] weights={70,60,60,120,40,10};int[] allocations=new int[6];int allocated=0;
        for(int i=0;i<6;i++){allocations[i]=budget*weights[i]/360;allocated+=allocations[i];}
        for(int i=0;allocated<budget;i=(i+1)%6){allocations[i]++;allocated++;}
        int index=0;
        for(var part:TITLES.entrySet()) {
            var row=new LinkedHashMap<>(activity);row.put("reportSection",part.getKey());
            row.put("minutes",allocations[index++]);
            row.put("sectionTitle",part.getValue());
            row.put("outcome",tasks.getOrDefault(part.getKey(),part.getKey().equals("REVIEW")
                ?"Summarize what you learned, any blockers and your next study steps."
                :"Record your "+part.getValue().toLowerCase(Locale.ROOT)+" for "+activity.get("title")+". If none was assigned, record a skip reason."));
            result.add(row);
        }
        return result;
    }
    static ObjectNode merge(Json json,JsonNode previous,String section,ObjectNode update,String title) {
        if(!TITLES.containsKey(section)) throw Problem.invalid("Choose a daily report section.");
        ObjectNode result=previous.isObject()?((ObjectNode)previous).deepCopy():json.object();
        ObjectNode sections=result.path("dailySections").isObject()?((ObjectNode)result.path("dailySections")).deepCopy():json.object();
        // Retain an existing combined update as the concepts update, with its original copy for review.
        if(sections.isEmpty()&&previous.isObject()&&!previous.isEmpty()) {
            result.set("legacyWork",previous.deepCopy());sections.set("CONCEPT",previous.deepCopy());
        }
        update.put("sectionVersion",sections.path(section).path("sectionVersion").asLong()+1);
        sections.set(section,update);result.set("dailySections",sections);
        int minutes=0;boolean done=true,skipped=true,started=false,blocked=false;
        var notes=new ArrayList<String>();
        for(String domain:TITLES.keySet()) {
            var value=sections.path(domain);String status=value.path("status").asText("NOT_STARTED");
            minutes+=value.path("actualMinutes").asInt();
            if(!domain.equals("REVIEW")) {
                done&=status.equals("DONE");skipped&=status.equals("SKIPPED")||status.equals("NOT_STARTED")&&value.path("skipReason").asText().trim().length()>=3;
            }
            started|=!status.equals("NOT_STARTED");blocked|=status.equals("BLOCKED");
            if(!value.path("notes").asText().isBlank())notes.add(TITLES.get(domain)+": "+value.path("notes").asText());
        }
        result.put("actualMinutes",minutes).put("status",done?"DONE":skipped?"SKIPPED":blocked?"BLOCKED":started?"IN_PROGRESS":"NOT_STARTED")
            .put("activityTitle",title).put("notes",String.join("\n",notes));
        return result;
    }
    static List<String> issues(JsonNode data) {
        var result=new ArrayList<String>();
        if(!data.path("dailySections").isObject())return result;
        for(String domain:TITLES.keySet()) {
            // Chronis requires the five work domains; the review section is optional.
            if(domain.equals("REVIEW"))continue;
            var entry=data.path("dailySections").path(domain);
            String status=entry.path("status").asText("NOT_STARTED"),exception=entry.path("skipReason").asText().trim();
            if(entry.isMissingNode()||status.equals("NOT_STARTED")&&exception.length()<3)
                result.add(TITLES.get(domain)+": save progress or an explicit skip reason.");
            else if(!status.equals("SKIPPED")&&!status.equals("NOT_STARTED")) {
                if(entry.path("evidence").asText().isBlank()&&exception.length()<3)
                    result.add(TITLES.get(domain)+": add evidence or an evidence exception reason.");
                if((domain.equals("CONCEPT")||domain.equals("INTERVIEW"))&&entry.path("confidence").asInt()<1)
                    result.add(TITLES.get(domain)+": choose confidence 1–5.");
                if(domain.equals("BASIC_CODING")&&status.equals("DONE")&&entry.path("validation").asText().isBlank())
                    result.add(TITLES.get(domain)+": add tests and output.");
                if(domain.equals("SCENARIO")&&Set.of("DONE","PARTIAL").contains(status)&&(entry.path("complexity").asText().isBlank()||entry.path("edgeCases").asText().isBlank()))
                    result.add(TITLES.get(domain)+": describe complexity and edge cases.");
                if(domain.equals("PROJECT")&&Set.of("DONE","PARTIAL").contains(status)&&entry.path("repository").asText().isBlank())
                    result.add(TITLES.get(domain)+": add a Git commit or demo reference.");
            }
        }
        return result;
    }
}

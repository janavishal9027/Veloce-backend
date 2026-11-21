package com.veloce.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloce.common.*;
import java.time.*;
import java.util.*;

public final class StudyRules {
    public static final Set<String> STATUSES=Set.of("NOT_STARTED","IN_PROGRESS","PARTIAL","BLOCKED","DONE","SKIPPED");
    public static final Set<String> SECTIONS=Set.of("DESIGN","IMPLEMENTATION","SCENARIOS","TESTING","DELIVERY","REVIEW");
    private StudyRules() {}
    public static long version(JsonNode input) {
        var value=input.path("expectedVersion");
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong()<0)
            throw Problem.invalid("A valid expectedVersion is required.");
        return value.asLong();
    }
    public static UUID mutation(JsonNode input) {
        try { return UUID.fromString(Json.text(input,"mutationId",36,true)); }
        catch (IllegalArgumentException e) { throw Problem.invalid("Use a valid save identifier."); }
    }
    public static int number(JsonNode input,String field,int min,int max,int fallback) {
        var value=input.path(field);
        if (value.isMissingNode()) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt()<min || value.asInt()>max)
            throw Problem.invalid("Enter "+field+" between "+min+" and "+max+".");
        return value.asInt();
    }
    public static LocalDate date(String input) {
        try {
            var date=LocalDate.parse(input);
            if (date.getYear()<2000 || date.getYear()>2200) throw new DateTimeException("Date range");
            return date;
        } catch (DateTimeException e) { throw Problem.invalid("Choose a valid study date."); }
    }
    public static ObjectNode work(Json json,JsonNode input,boolean draft) {
        return work(json,input,draft,1440);
    }
    public static ObjectNode work(Json json,JsonNode input,boolean draft,int maximumMinutes) {
        if (!input.isObject()) throw Problem.invalid("Enter the work details.");
        ObjectNode clean=json.object();
        String status=Json.text(input,"status",20,true);
        if (!STATUSES.contains(status)) throw Problem.invalid("Choose a valid work status.");
        clean.put("status",status).put("actualMinutes",number(input,"actualMinutes",0,maximumMinutes,0))
            .put("confidence",number(input,"confidence",0,5,0));
        for (String field:List.of("notes","code","evidence","validation","complexity","edgeCases","repository","blockers","nextSteps","skipReason","correctionReason")) {
            int max=field.equals("code")?20_000:field.equals("notes")||field.equals("validation")?12_000:4_000;
            clean.put(field,Json.text(input,field,max,false));
        }
        if (input.hasNonNull("score")) clean.put("score",number(input,"score",0,100,0));
        if (!draft) {
            if (status.equals("BLOCKED") && clean.path("blockers").asText().length()<3)
                throw Problem.invalid("Describe what is blocking this activity.");
            if (status.equals("SKIPPED") && (clean.path("skipReason").asText().length()<3 || clean.path("actualMinutes").asInt()!=0))
                throw Problem.invalid("Give a skip reason and use zero actual minutes for skipped work.");
            if (status.equals("DONE") && clean.path("notes").asText().isBlank() && clean.path("code").asText().isBlank()
                && clean.path("evidence").asText().isBlank())
                throw Problem.invalid("Describe the completed work or provide its evidence.");
        }
        return clean;
    }
    public static List<String> reportIssues(List<StudyStore.Record> entries,List<String> planned) {
        var byNode=new HashMap<String,StudyStore.Record>();
        for (var entry:entries) byNode.put(entry.nodeId(),entry);
        var issues=new ArrayList<String>();
        for (String id:planned) {
            var entry=byNode.get(id);
            if (entry==null) issues.add(id+": save work or an explicit skip reason.");
            else if (entry.data().path("status").asText().equals("NOT_STARTED"))
                issues.add(id+": record progress or choose Skipped with a reason.");
        }
        if (entries.isEmpty()) issues.add("Save at least one work record before submitting a report.");
        for(var entry:entries)for(String issue:DailySections.issues(entry.data()))issues.add(entry.nodeId()+": "+issue);
        return issues;
    }
    public static String csv(String value) {
        String safe=value==null?"":value;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0))>=0) safe="'"+safe;
        return "\""+safe.replace("\"","\"\"")+"\"";
    }
}

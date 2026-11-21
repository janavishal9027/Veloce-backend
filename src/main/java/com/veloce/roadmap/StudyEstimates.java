package com.veloce.roadmap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloce.common.Problem;
import com.veloce.roadmap.ImportParser.Analysis;
import com.veloce.roadmap.ImportParser.StudyEstimate;
import java.util.*;

/** Reviewable suggestions; imported, valid study times always take precedence. */
final class StudyEstimates {
    record Applied(JsonNode candidate,List<StudyEstimate> estimates) {}

    static boolean missing(JsonNode node) {
        var value=node.path("estimatedMinutes");
        return !node.path("kind").asText().equals("MODULE")
            && (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt()<1 || value.asInt()>43_200);
    }

    static List<StudyEstimate> suggest(Analysis analysis,JsonNode candidate) {
        var locations=new HashMap<String,String>();
        for (int i=0;i<analysis.candidate().path("nodes").size() && i<analysis.locations().size();i++)
            locations.put(analysis.candidate().path("nodes").get(i).path("id").asText(),analysis.locations().get(i));
        var result=new ArrayList<StudyEstimate>();
        for (var node:candidate.path("nodes")) {
            if (!missing(node)) continue;
            int minutes=switch(node.path("kind").asText()) {
                case "LESSON" -> 45;
                case "PRACTICE" -> 25;
                case "PROJECT" -> 120;
                case "REVIEW" -> 30;
                case "ASSESSMENT" -> 60;
                default -> 0;
            };
            if (minutes==0) continue;
            String basis="Suggested "+node.path("kind").asText().toLowerCase(Locale.ROOT)+" time";
            String source=locations.getOrDefault(node.path("id").asText(),"");
            for (var sheet:analysis.sheets()) {
                if (!source.startsWith(sheet.name()+" · row ")) continue;
                boolean interview=sheet.headers().stream().anyMatch(h->h.replaceAll("[^a-zA-Z]","").equalsIgnoreCase("answerstructure"));
                if (interview && node.path("kind").asText().equals("REVIEW")) {
                    minutes=10;
                    basis="Suggested interview time";
                }
                break;
            }
            result.add(new StudyEstimate(node.path("id").asText(),minutes,basis));
        }
        return result;
    }

    static Applied apply(Analysis old,JsonNode candidate,JsonNode selections) {
        if (!candidate.isObject() || !candidate.path("nodes").isArray() || candidate.path("nodes").isEmpty()
            || candidate.path("nodes").size()>ImportParser.NODE_LIMIT)
            throw Problem.invalid("Submit the current roadmap to set study estimates.");
        if (!selections.isArray() || selections.isEmpty() || selections.size()>ImportParser.NODE_LIMIT)
            throw Problem.invalid("Choose study estimates to apply.");
        ObjectNode updated=candidate.deepCopy();
        var nodes=new HashMap<String,ObjectNode>();
        for (var node:updated.path("nodes")) {
            if (!node.isObject() || nodes.putIfAbsent(node.path("id").asText(),(ObjectNode)node)!=null)
                throw Problem.invalid("Use unique activity IDs before applying study estimates.");
        }
        var suggestions=new HashMap<String,StudyEstimate>();
        for (var suggestion:suggest(old,candidate)) suggestions.put(suggestion.nodeId(),suggestion);
        var applied=new LinkedHashMap<String,StudyEstimate>();
        for (var estimate:old.assignedEstimates()) {
            var node=nodes.get(estimate.nodeId());
            if (node!=null && !missing(node) && node.path("estimatedMinutes").asInt()==estimate.minutes())
                applied.put(estimate.nodeId(),estimate);
        }
        var seen=new HashSet<String>();
        for (var selection:selections) {
            String id=selection.path("nodeId").asText();
            var value=selection.path("minutes");
            var node=nodes.get(id);
            if (!seen.add(id) || node==null || !missing(node) || !suggestions.containsKey(id)
                || !value.isIntegralNumber() || !value.canConvertToInt() || value.asInt()<1 || value.asInt()>43_200)
                throw Problem.invalid("Choose 1 to 43,200 minutes for each activity with a missing estimate.");
            var suggestion=suggestions.get(id);
            int minutes=value.asInt();
            node.put("estimatedMinutes",minutes);
            applied.put(id,new StudyEstimate(id,minutes,minutes==suggestion.minutes()
                ? suggestion.basis() : "Study time chosen during import"));
        }
        return new Applied(updated,List.copyOf(applied.values()));
    }
}

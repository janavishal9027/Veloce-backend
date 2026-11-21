package com.veloce.roadmap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.veloce.roadmap.ImportParser.*;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** Shared import cleanup. Source bytes are retained; structural validation still applies. */
final class ImportPreparation {
    private static final Set<String> KINDS=Set.of("MODULE","LESSON","PRACTICE","PROJECT","REVIEW","ASSESSMENT");
    private static final Pattern LINKS=Pattern.compile(
        "(?:https?://|www\\.)[^\\s<>\"|]*?(?=(?:https?://|www\\.)|[\\s<>\"|]|$)",Pattern.CASE_INSENSITIVE);

    static Analysis prepare(ImportParser parser,Analysis old,JsonNode profile) {
        if (!old.candidate().isObject()) return old;
        ObjectNode doc=old.candidate().deepCopy();
        var notes=new LinkedHashSet<>(old.preparationNotes());
        var defaults=parser.document(profile);
        for(var field:List.of("title","industry","targetRole")) {
            if(doc.path(field).isMissingNode()||doc.path(field).isNull()||doc.path(field).asText().isBlank())
                doc.set(field,defaults.path(field));
            else if(doc.path(field).isTextual()) doc.put(field,doc.path(field).asText().strip());
        }
        int outcomes=0,links=0;
        for(var raw:doc.path("nodes")) {
            if(!raw.isObject()) continue;
            var node=(ObjectNode)raw;
            for(var field:List.of("title","outcome"))
                if(node.path(field).isTextual()) node.put(field,node.path(field).asText().strip());
            String kind=node.path("kind").asText().strip().toUpperCase(Locale.ROOT);
            if(kind.isBlank()) kind="LESSON";
            if(KINDS.contains(kind)) node.put("kind",kind);
            if(!node.has("parentId")) node.putNull("parentId");
            if(!node.has("dependsOn")) node.putArray("dependsOn");
            if(!node.hasNonNull("priority")||node.path("priority").asText().isBlank()) node.put("priority","MUST");
            else if(node.path("priority").isTextual()) node.put("priority",node.path("priority").asText().strip().toUpperCase(Locale.ROOT));
            if(kind.equals("MODULE")) node.put("estimatedMinutes",0);
            if(node.path("outcome").asText().isBlank()&&!node.path("title").asText().isBlank()) {
                node.put("outcome",kind.equals("MODULE")?"Complete the activities in this section.":
                    "Explain "+node.path("title").asText()+" and demonstrate the result with a practical example.");
                outcomes++;
            }
            var resources=resources(node.path("resources"));
            if(!resources.equals(node.path("resources"))) links++;
            node.set("resources",resources);
        }
        var assigned=new LinkedHashMap<String,StudyEstimate>();
        var byId=new HashMap<String,JsonNode>();
        doc.path("nodes").forEach(n->byId.put(n.path("id").asText(),n));
        for(var estimate:old.assignedEstimates()) {
            var node=byId.get(estimate.nodeId());
            if(node!=null&&!StudyEstimates.missing(node)&&node.path("estimatedMinutes").asInt()==estimate.minutes())
                assigned.put(estimate.nodeId(),estimate);
        }
        var suggestions=StudyEstimates.suggest(old,doc);
        for(var suggestion:suggestions) {
            var node=byId.get(suggestion.nodeId());
            if(node instanceof ObjectNode object) object.put("estimatedMinutes",suggestion.minutes());
            assigned.put(suggestion.nodeId(),new StudyEstimate(suggestion.nodeId(),suggestion.minutes(),
                suggestion.basis().replace("Suggested ","Automatic ")));
        }
        if(links>0) notes.add("Resource links were cleaned, separated and checked automatically. Original source content is retained.");
        if(outcomes>0) notes.add("Added editable learning outcomes to "+outcomes+" topics.");
        if(!suggestions.isEmpty()) notes.add("Applied editable study estimates to "+suggestions.size()+" activities with missing or invalid study time.");
        var warnings=new ArrayList<String>();
        for(var warning:old.warnings()) {
            if(warning.startsWith("Review study time for ")&&!byId.values().stream().anyMatch(StudyEstimates::missing)) continue;
            if(!doc.path("nodes").isEmpty()&&(warning.startsWith("Left ")||warning.startsWith("Skipped ")
                ||warning.startsWith("The outline was inferred."))) notes.add(warning);
            else warnings.add(warning);
        }
        return new Analysis(doc,List.copyOf(warnings),parser.issues(doc),old.headers(),old.rows(),old.mapping(),old.locations(),
            old.extractedText(),old.sheets(),List.copyOf(assigned.values()),List.copyOf(notes),old.workbookSchedule());
    }

    private static ArrayNode resources(JsonNode input) {
        var result=JsonNodeFactory.instance.arrayNode();
        var found=new LinkedHashMap<String,String>();
        if(input.isArray()) for(var resource:input) {
            String title=resource.path("title").asText("").strip();
            if(title.isBlank()) title="Resource";
            if(title.length()>200) title=title.substring(0,200);
            String raw=resource.isTextual()?resource.asText():resource.path("url").asText("");
            var matches=LINKS.matcher(raw);
            while(matches.find()&&found.size()<20) {
                String url=matches.group();
                while(!url.isEmpty()&&(url.endsWith(";")||url.endsWith(",")||url.endsWith(".")||url.endsWith("]")||url.endsWith("}")))
                    url=url.substring(0,url.length()-1);
                while(url.endsWith(")")&&url.chars().filter(c->c==')').count()>url.chars().filter(c->c=='(').count())
                    url=url.substring(0,url.length()-1);
                if(url.regionMatches(true,0,"www.",0,4)) url="https://"+url;
                int colon=url.indexOf(':');
                if(colon>0) url=url.substring(0,colon).toLowerCase(Locale.ROOT)+url.substring(colon);
                try {
                    var uri=URI.create(url);
                    if(url.length()<=2048&&uri.getHost()!=null&&!uri.getHost().isBlank()
                        &&Set.of("http","https").contains(uri.getScheme())&&uri.getUserInfo()==null)
                        found.putIfAbsent(url,title);
                } catch(IllegalArgumentException ignored) { }
            }
        }
        found.forEach((url,title)->result.addObject().put("title",title).put("url",url));
        return result;
    }
}

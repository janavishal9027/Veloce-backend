package com.veloce.roadmap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.networknt.schema.*;
import com.networknt.schema.dialect.Dialects;
import com.veloce.common.*;
import java.net.URI;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class Roadmap {
    public enum Kind { MODULE, LESSON, PRACTICE, PROJECT, REVIEW, ASSESSMENT }
    public enum Priority { MUST, SHOULD, OPTIONAL }
    public record Resource(String title,String url) {}
    public record Node(String id,String parentId,String title,Kind kind,Integer estimatedMinutes,
                       List<String> dependsOn,Priority priority,String outcome,List<Resource> resources) {}
    public record Document(int schemaVersion,String title,String industry,String targetRole,List<Node> nodes) {}
    public record Issue(String location,String message) {}
    private final Json json;
    private final Schema schema;
    private final JsonNode contract;
    public Roadmap(Json json) throws java.io.IOException {
        this.json=json;
        try (var input=new ClassPathResource("contracts/roadmap-v1.schema.json").getInputStream()) {
            contract=json.mapper.readTree(input);
        }
        schema=SchemaRegistry.withDialect(Dialects.getDraft202012()).getSchema(contract);
    }
    public JsonNode contract() { return contract.deepCopy(); }

    public List<Issue> issues(JsonNode input) {
        var problems=new ArrayList<Issue>();
        schema.validate(input).stream().limit(80).forEach(error ->
            problems.add(new Issue(error.getInstanceLocation().toString(),error.getMessage())));
        if (!problems.isEmpty()) return problems;
        var document=json.as(input,Document.class);
        if (document.title().isBlank() || document.industry().isBlank() || document.targetRole().isBlank())
            problems.add(new Issue("$","Add a title, industry, and target role."));
        var byId=new LinkedHashMap<String,Node>();
        var parents=new HashSet<String>();
        int leaves=0;
        for (var node:document.nodes()) {
            if (node.kind()!=Kind.MODULE) leaves++;
            if (byId.putIfAbsent(node.id(),node)!=null) problems.add(new Issue(node.id(),"Node IDs must be unique."));
            if (node.parentId()!=null) parents.add(node.parentId());
            if (node.title().isBlank() || node.outcome().isBlank()) problems.add(new Issue(node.id(),"Add a title and an assessable outcome."));
            for (var resource:node.resources()) {
                try {
                    if (resource.title().isBlank() || URI.create(resource.url()).getHost()==null)
                        throw new IllegalArgumentException();
                } catch (IllegalArgumentException ex) { problems.add(new Issue(node.id(),"Use a titled HTTP(S) resource with a valid host.")); }
            }
        }
        if (leaves==0) problems.add(new Issue("$.nodes","Add at least one study activity."));
        for (var node:byId.values()) {
            if (node.parentId()!=null && (node.id().equals(node.parentId()) || !byId.containsKey(node.parentId())
                || byId.get(node.parentId()).kind()!=Kind.MODULE)) problems.add(new Issue(node.id(),"Parent must be a different module."));
            if (node.kind()==Kind.MODULE && !parents.contains(node.id())) problems.add(new Issue(node.id(),"Remove this empty module or add topics to it."));
            for (String dep:node.dependsOn()) {
                if (dep.equals(node.id()) || !byId.containsKey(dep) || byId.get(dep).kind()==Kind.MODULE)
                    problems.add(new Issue(node.id(),"Prerequisite "+dep+" must identify a different study activity."));
                if (problems.size()>=80) return List.copyOf(problems.subList(0,80));
            }
        }
        if (problems.isEmpty() && cyclic(byId,false)) problems.add(new Issue("$.nodes","Module hierarchy contains a cycle."));
        if (problems.isEmpty() && cyclic(byId,true)) problems.add(new Issue("$.nodes","Prerequisites contain a cycle."));
        return problems.stream().limit(80).toList();
    }

    public Document confirmed(JsonNode input) {
        List<Issue> issues=issues(input);
        if (!issues.isEmpty()) {
            long estimates=java.util.stream.StreamSupport.stream(input.path("nodes").spliterator(),false)
                .filter(node -> !node.path("kind").asText().equals("MODULE"))
                .map(node -> node.path("estimatedMinutes"))
                .filter(value -> !value.isIntegralNumber() || !value.canConvertToInt()
                    || value.asInt()<1 || value.asInt()>43200).count();
            String message=estimates>0
                ? "Add valid study estimates for "+estimates+" "+(estimates==1?"activity":"activities")+" before confirming."
                : "Resolve the roadmap issues before confirming.";
            throw new Problem(422,"ROADMAP_INVALID",message,issues);
        }
        var d=json.as(input,Document.class);
        return new Document(1,d.title().strip(),d.industry().strip(),d.targetRole().strip(),
            d.nodes().stream().map(n -> new Node(n.id(),n.parentId(),n.title().strip(),n.kind(),n.estimatedMinutes(),
                n.dependsOn(),n.priority(),n.outcome().strip(),n.resources().stream().map(r -> new Resource(r.title().strip(),r.url())).toList())).toList());
    }

    private boolean cyclic(Map<String,Node> nodes,boolean dependency) {
        var incoming=new HashMap<String,Integer>();
        var forward=new HashMap<String,List<String>>();
        var ready=new ArrayDeque<String>();
        for (var n:nodes.values()) {
            List<String> predecessors=dependency?n.dependsOn():n.parentId()==null?List.of():List.of(n.parentId());
            incoming.put(n.id(),predecessors.size());
            if (predecessors.isEmpty()) ready.add(n.id());
            for (String pred:predecessors) forward.computeIfAbsent(pred,k->new ArrayList<>()).add(n.id());
        }
        int visited=0;
        while (!ready.isEmpty()) {
            visited++;
            for (String next:forward.getOrDefault(ready.remove(),List.of())) if (incoming.merge(next,-1,Integer::sum)==0) ready.add(next);
        }
        return visited!=nodes.size();
    }
}


package com.veloce.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloce.common.*;
import com.veloce.workspace.WorkspaceStore.Workspace;
import java.math.*;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

public final class ProjectCatalog {
    public record Project(String id,int number,String title,String outcome,int minutes,JsonNode resources,
        int completedSections,String status,boolean scheduledToday,String externalId,String level,String stack,
        String category,String origin,String scope,String extension,boolean supplemental,boolean fullBuildEstimate,
        Map<String,String> sections) {
        public Project(String id,int number,String title,String outcome,int minutes,JsonNode resources,
            int completedSections,String status,boolean scheduledToday) {
            this(id,number,title,outcome,minutes,resources,completedSections,status,scheduledToday,
                "P"+String.format(Locale.ROOT,"%02d",number),"","","","","","",false,false,Map.of());
        }
        String storageNodeId() { return supplemental?null:id; }
    }
    private record Node(String id,String title,String outcome,String kind,int minutes,JsonNode resources) {}
    record Backlog(String id,String externalId,String title,String outcome,int minutes,JsonNode resources,
        String level,String stack,String category,String origin,String scope,String extension) {}
    private final StudyStore s;
    ProjectCatalog(StudyStore store) { this.s=store; }

    List<Project> list(Workspace workspace,LocalDate today) {
        var w=workspace.id();var roadmap=s.roadmap(workspace);
        var nodes=s.ws.db.query("""
            SELECT node_id,title,outcome,kind,estimated_minutes,resources FROM roadmap_nodes
            WHERE workspace_id=? AND roadmap_id=? ORDER BY ordinal,node_id
            """,(r,n)->new Node(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getInt(5),s.json.read(r.getString(6))),w,roadmap);
        var states=new HashMap<String,Map<String,String>>();
        s.ws.db.query("""
            SELECT coalesce(node_id,split_part(record_key,'/',1)) AS id,
              split_part(record_key,'/',2) AS section,coalesce(data->>'status','NOT_STARTED') AS status
            FROM study_records WHERE workspace_id=? AND roadmap_id=? AND record_type='PROJECT'
            """,r->{if(StudyRules.SECTIONS.contains(r.getString("section")))
                states.computeIfAbsent(r.getString("id"),x->new HashMap<>()).put(r.getString("section"),r.getString("status"));},w,roadmap);
        var scheduled=new HashSet<>(s.ws.db.queryForList("""
            SELECT DISTINCT node_id FROM schedule_slots WHERE workspace_id=? AND roadmap_id=? AND plan_id=?
              AND study_date=? AND kind='STUDY' AND NOT cancelled
            """,String.class,w,roadmap,workspace.activePlanId(),today));
        var texts=nodes.stream().filter(n->scheduled.contains(n.id())).map(n->assignment(n.title()+"\n"+n.outcome())).toList();
        var source=s.ws.db.query("""
            SELECT src.analysis->'sheets' FROM roadmap_versions v LEFT JOIN roadmap_sources src
              ON src.workspace_id=v.workspace_id AND src.id=v.source_id
            WHERE v.workspace_id=? AND v.id=?
            """,(r,n)->r.getString(1),w,roadmap);
        JsonNode sheets=source.isEmpty()||source.getFirst()==null?s.json.mapper.createArrayNode():s.json.read(source.getFirst());
        var result=new ArrayList<Project>();var used=new HashSet<String>();
        for(var bank:backlog(sheets,s.json)) {
            var match=nodes.stream().filter(n->n.kind().equals("PROJECT")&&!used.contains(n.id()))
                .filter(n->n.id().equalsIgnoreCase(bank.externalId())||normal(n.title()).equals(normal(bank.title()))).findFirst().orElse(null);
            String id=match==null?bank.id():match.id();
            if(!used.add(id)) continue;
            boolean assigned=scheduled.contains(id)||referenced(bank,texts);
            var resources=match!=null&&!match.resources().isEmpty()?match.resources():bank.resources();
            int minutes=bank.minutes()>0?bank.minutes():match==null?0:match.minutes();
            result.add(project(id,result.size()+1,bank.title(),bank.outcome(),minutes,resources,states.get(id),assigned,
                bank.externalId(),bank.level(),bank.stack(),bank.category(),bank.origin(),bank.scope(),bank.extension(),match==null,true));
        }
        for(var node:nodes) if(node.kind().equals("PROJECT")&&used.add(node.id())) {
            result.add(project(node.id(),result.size()+1,node.title(),node.outcome(),node.minutes(),node.resources(),states.get(node.id()),
                scheduled.contains(node.id()),"","","","","","","",false,false));
        }
        return List.copyOf(result);
    }
    Project find(Workspace workspace,String id) {
        return list(workspace,s.today(workspace)).stream().filter(p->p.id().equals(id)).findFirst().orElseThrow(Problem::missing);
    }
    private static Project project(String id,int number,String title,String outcome,int minutes,JsonNode resources,
        Map<String,String> states,boolean today,String external,String level,String stack,String category,String origin,
        String scope,String extension,boolean supplemental,boolean fullBuild) {
        var sections=states==null?Map.<String,String>of():Map.copyOf(states);
        int done=(int)sections.values().stream().filter("DONE"::equals).count();
        String status=done==6?"DONE":sections.containsValue("BLOCKED")?"BLOCKED":
            sections.values().stream().anyMatch(v->!v.equals("NOT_STARTED"))?"IN_PROGRESS":"NOT_STARTED";
        return new Project(id,number,title,outcome,minutes,resources,done,status,today,
            external.isBlank()?"P"+String.format(Locale.ROOT,"%02d",number):external,
            level,stack,category,origin,scope,extension,supplemental,fullBuild,sections);
    }
    static List<Backlog> backlog(JsonNode sheets,Json json) {
        var rows=new ArrayList<Backlog>();var seen=new HashSet<String>();
        for(var sheet:sheets) {
            String name=normal(sheet.path("name").asText());
            if(!(name.contains("project")||name.contains("portfolio"))||name.contains("report")) continue;
            int title=column(sheet,"title","realworldproject","projectname","projecttitle","project","title");
            if(title<0) continue;
            int id=column(sheet,"id","id","projectid"),outcome=column(sheet,"outcome","acceptancecriteria","outcome","deliverable"),
                minutes=column(sheet,"estimatedMinutes","fullbuildesthrs","estimatedhours","hours","estimatedminutes","minutes"),
                level=column(sheet,null,"level","difficulty"),stack=column(sheet,null,"techstack","stack","technologies"),
                category=column(sheet,null,"portfoliopriority","category","type"),origin=column(sheet,null,"source","origin"),
                scope=column(sheet,null,"scopeprototype","scopemvp","scope","description"),extension=column(sheet,null,"fullbuildextension","extension"),
                repository=column(sheet,"resourceUrl","repositoryurl","resourcelink","resourceurl");
            for(var row:sheet.path("rows")) {
                String label=cell(row,title); if(label.isBlank()) continue;
                String identity=cell(row,id);
                if(!seen.add(normal(identity)+"|"+normal(label))) continue;
                String stable=identity.isBlank()?row.path("location").asText(label):identity.toLowerCase(Locale.ROOT);
                String key="backlog_"+Json.sha256(sheet.path("id").asText()+"\n"+stable).substring(0,24);
                var resources=json.mapper.createArrayNode();String link=cell(row,repository);
                try {var uri=URI.create(link);if(uri.getScheme()!=null&&Set.of("http","https").contains(uri.getScheme())&&uri.getHost()!=null)
                    resources.add(json.object().put("label","Project repository").put("url",link));}
                catch(IllegalArgumentException ignored) {}
                String objective=cell(row,outcome);if(objective.isBlank()) objective=cell(row,scope);
                String header=minutes>=0?sheet.path("headers").path(minutes).asText():"";
                rows.add(new Backlog(key,identity,label,objective,estimate(cell(row,minutes),header),resources,
                    cell(row,level),cell(row,stack),cell(row,category),cell(row,origin),cell(row,scope),cell(row,extension)));
            }
        }
        return List.copyOf(rows);
    }
    static String assignment(String text) {
        // A project range in a deliverable or reference note is not today's assigned project work.
        boolean structured=text.lines().anyMatch(line->Set.of("Project workstream","Project sprint").contains(line.trim()));
        if(!structured)return text;
        var result=new StringBuilder();boolean selected=false;
        for(String line:text.split("\\R")) {
            if(Set.of("Project workstream","Project sprint").contains(line.trim()))selected=true;
            else if(line.isBlank())selected=false;
            else if(selected)result.append(line).append('\n');
        }
        return result.toString();
    }
    static boolean referenced(Backlog project,List<String> assignments) {
        String id=project.externalId();
        if(id.matches("(?i)(?=.*[a-z])(?=.*[0-9])[a-z0-9_-]+")) {
            var pattern=Pattern.compile("(?<![a-z0-9])"+Pattern.quote(id)+"(?![a-z0-9])",Pattern.CASE_INSENSITIVE);
            return assignments.stream().anyMatch(text->pattern.matcher(text).find());
        }
        if(project.title().length()<8) return false;
        var pattern=Pattern.compile("(?<![a-z0-9])"+Pattern.quote(project.title())+"(?![a-z0-9])",Pattern.CASE_INSENSITIVE);
        return assignments.stream().anyMatch(text->pattern.matcher(text).find());
    }
    private static int column(JsonNode sheet,String mapping,String... aliases) {
        var mapped=mapping==null?null:sheet.path("mapping").get(mapping);
        if(mapped!=null&&mapped.isIntegralNumber()&&mapped.asInt()>=0&&mapped.asInt()<sheet.path("headers").size()) return mapped.asInt();
        for(int i=0;i<sheet.path("headers").size();i++) {
            String header=normal(sheet.path("headers").get(i).asText());
            for(var alias:aliases) if(header.equals(alias)) return i;
        }
        return -1;
    }
    private static String normal(String value) { return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]",""); }
    private static String cell(JsonNode row,int index) { return index<0?"":row.path("cells").path(index).asText("").strip(); }
    static int estimate(String value,String header) {
        var match=Pattern.compile("(?i)^([0-9]+(?:\\.[0-9]+)?)\\s*(h|hr|hrs|hours?|m|min|mins|minutes?)?$").matcher(value);
        if(!match.matches()) return 0;
        String unit=match.group(2);
        boolean hours=unit!=null?unit.toLowerCase(Locale.ROOT).startsWith("h"):
            normal(header).contains("hour")||normal(header).contains("hrs");
        try {
            var amount=new BigDecimal(match.group(1)).multiply(BigDecimal.valueOf(hours?60:1)).setScale(0,RoundingMode.CEILING);
            return amount.signum()>0&&amount.compareTo(BigDecimal.valueOf(43_200))<=0?amount.intValueExact():0;
        } catch(ArithmeticException e) { return 0; }
    }
}


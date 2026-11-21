package com.veloce.study;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Small presentation summaries; report evidence always comes from submitted snapshots. */
public final class StudyView {
    public record Node(String id,String parent,String title,String kind,int ordinal) {}
    public record Block(LocalDate date,String node,int minutes,int slots,int completed) {}
    public record Work(LocalDate date,String node,String status,int minutes,long version) {}
    public record Entry(String node,String status,int minutes,long version) {}
    public record Report(LocalDate date,long version,List<Entry> entries) {}
    public record Section(String node,String section,String status) {}
    private StudyView() {}
    private static int total(Collection<? extends Number> values) { return values.stream().mapToInt(Number::intValue).sum(); }

    public static Map<String,Object> build(String title,List<Node> nodes,List<Block> blocks,List<Work> work,
        List<Report> reports,Set<LocalDate> attendance,Map<LocalDate,Integer> focus,List<Section> sections,Map<String,Integer> practice) {
        var byId=new LinkedHashMap<String,Node>();
        for (var node:nodes) byId.put(node.id(),node);
        var phaseNames=new LinkedHashMap<String,String>();
        for (var node:nodes) if (node.kind().equals("MODULE")&&(node.parent()==null||!hasModuleParent(node,byId)))
            phaseNames.put(node.id(),node.title());
        String fallback="__roadmap__";
        if (phaseNames.isEmpty()) phaseNames.put(fallback,title);
        var phaseFor=new HashMap<String,String>();
        for (var node:nodes) {
            Node current=node;String phase=null;var visited=new HashSet<String>();
            while (current!=null&&visited.add(current.id())) {
                if (phaseNames.containsKey(current.id())) phase=current.id();
                current=byId.get(current.parent());
            }
            if (phase==null) { phase=fallback;phaseNames.putIfAbsent(fallback,title); }
            phaseFor.put(node.id(),phase);
        }
        var planned=new TreeMap<LocalDate,List<Block>>();
        for (var block:blocks) planned.computeIfAbsent(block.date(),x->new ArrayList<>()).add(block);
        var saved=new HashMap<LocalDate,List<Work>>();
        for (var row:work) saved.computeIfAbsent(row.date(),x->new ArrayList<>()).add(row);
        var submitted=new HashMap<LocalDate,Report>();
        for (var report:reports) submitted.merge(report.date(),report,(a,b)->a.version()>b.version()?a:b);
        var days=new ArrayList<Map<String,Object>>();int number=0,completedDays=0;
        for (var item:planned.entrySet()) {
            LocalDate date=item.getKey();var rows=item.getValue();
            var ids=new LinkedHashSet<String>();var phases=new LinkedHashSet<String>();var kinds=new LinkedHashSet<String>();
            var titles=new ArrayList<String>();String dayTitle=null;
            for (var row:rows) {
                var node=byId.get(row.node());if (node==null) continue;
                if (ids.add(node.id())) titles.add(node.title());
                phases.add(phaseFor.get(node.id()));kinds.add(node.kind());
                if (dayTitle==null||node.kind().equals("LESSON")&&!kinds.contains("__lesson")) {
                    dayTitle=node.title();if(node.kind().equals("LESSON")) kinds.add("__lesson");
                }
            }
            kinds.remove("__lesson");
            var report=submitted.get(date);var current=saved.getOrDefault(date,List.of());
            var statuses=new HashMap<String,String>();var snapshotVersions=new HashMap<String,Long>();
            int reportMinutes=0;
            if (report!=null) for (var entry:report.entries()) {
                statuses.put(entry.node(),entry.status());snapshotVersions.put(entry.node(),entry.version());reportMinutes+=entry.minutes();
            }
            boolean complete=report!=null&&!ids.isEmpty()&&ids.stream().allMatch(id->"DONE".equals(statuses.get(id)));
            if (complete) completedDays++;
            var currentVersions=new HashMap<String,Long>();
            for(var row:current)currentVersions.put(row.node(),row.version());
            boolean fresh=report!=null&&snapshotVersions.equals(currentVersions)&&ids.stream().allMatch(id->statuses.containsKey(id)&&!statuses.get(id).equals("NOT_STARTED"));
            String status=complete?"Complete":report!=null?"Report submitted":current.stream().anyMatch(x->!x.status().equals("NOT_STARTED"))?"In progress":"Not started";
            var day=new LinkedHashMap<String,Object>();
            day.put("date",date.toString());day.put("number",++number);day.put("title",dayTitle==null?title:dayTitle);
            String description=String.join(" · ",titles);day.put("description",description.substring(0,Math.min(520,description.length())));
            day.put("phaseIds",phases);day.put("nodeIds",ids);day.put("kinds",kinds);
            day.put("plannedMinutes",rows.stream().mapToInt(Block::minutes).sum());
            day.put("slots",rows.stream().mapToInt(Block::slots).sum());day.put("completedSlots",rows.stream().mapToInt(Block::completed).sum());
            day.put("status",status);day.put("hasReport",report!=null);day.put("reportComplete",complete);day.put("reportFresh",fresh);
            day.put("reportVersion",report==null?0:report.version());day.put("reportMinutes",reportMinutes);
            day.put("workMinutes",current.stream().mapToInt(Work::minutes).sum());day.put("focusSeconds",focus.getOrDefault(date,0));
            day.put("attendance",attendance.contains(date));
            var domains=new LinkedHashMap<String,String>();
            for(var row:rows) {
                var node=byId.get(row.node());if(node==null)continue;
                String value=statuses.getOrDefault(node.id(),"NOT_STARTED");
                domains.merge(node.kind(),value,(a,b)->a.equals("DONE")&&b.equals("DONE")?"DONE":"PARTIAL");
            }
            day.put("domains",domains);days.add(day);
        }
        var phases=new ArrayList<Map<String,Object>>();int phaseNumber=0;
        for(var phase:phaseNames.entrySet()) {
            var members=days.stream().filter(d->((Set<?>)d.get("phaseIds")).contains(phase.getKey())).toList();
            if(members.isEmpty()&&phase.getKey().equals(fallback)&&phaseNames.size()>1)continue;
            var result=new LinkedHashMap<String,Object>();result.put("id",phase.getKey());result.put("name",phase.getValue());result.put("number",++phaseNumber);
            result.put("days",members.size());result.put("reports",members.stream().filter(d->Boolean.TRUE.equals(d.get("hasReport"))).count());
            result.put("completeDays",members.stream().filter(d->Boolean.TRUE.equals(d.get("reportComplete"))).count());
            result.put("start",members.isEmpty()?"":members.getFirst().get("date"));result.put("end",members.isEmpty()?"":members.getLast().get("date"));
            result.put("minutes",members.stream().mapToInt(d->((Number)d.get("plannedMinutes")).intValue()).sum());
            phases.add(result);
        }
        var sectionStates=new HashMap<String,Map<String,String>>();
        for(var section:sections) if(StudyRules.SECTIONS.contains(section.section()))
            sectionStates.computeIfAbsent(section.node(),x->new HashMap<>()).put(section.section(),section.status());
        var projects=new LinkedHashMap<String,Object>();int projectNumber=0,notStarted=0,inProgress=0,blocked=0,done=0;
        for(var node:nodes) if(node.kind().equals("PROJECT")) {
            var states=sectionStates.getOrDefault(node.id(),Map.of());
            long count=states.values().stream().filter("DONE"::equals).count();
            String status=count==6?"DONE":states.containsValue("BLOCKED")?"BLOCKED":states.values().stream().anyMatch(x->!x.equals("NOT_STARTED"))?"IN_PROGRESS":"NOT_STARTED";
            switch(status) { case "DONE" -> done++;case "BLOCKED" -> blocked++;case "IN_PROGRESS" -> inProgress++;default -> notStarted++; }
            projects.put(node.id(),Map.of("number",++projectNumber,"completedSections",count,"status",status,"sections",states));
        }
        LocalDate first=planned.isEmpty()?submitted.keySet().stream().min(LocalDate::compareTo).orElse(LocalDate.now()):planned.firstKey();
        var weekRows=new TreeMap<Integer,Map<String,Object>>();
        var allDates=new TreeSet<LocalDate>();allDates.addAll(planned.keySet());allDates.addAll(submitted.keySet());allDates.addAll(focus.keySet());
        for(var date:allDates) {
            int week=(int)Math.floorDiv(ChronoUnit.DAYS.between(first,date),7)+1;
            var row=weekRows.computeIfAbsent(week,x->{
                var r=new LinkedHashMap<String,Object>();r.put("number",x);r.put("start",first.plusDays((x-1)*7L).toString());r.put("studyDays",0);r.put("plannedMinutes",0);r.put("focusSeconds",0);r.put("reportedMinutes",0);r.put("reports",0);return r;
            });
            if(planned.containsKey(date)) {
                add(row,"studyDays",1);add(row,"plannedMinutes",planned.get(date).stream().mapToInt(Block::minutes).sum());
            }
            add(row,"focusSeconds",focus.getOrDefault(date,0));
            var report=submitted.get(date);
            if(report!=null) {add(row,"reports",1);add(row,"reportedMinutes",report.entries().stream().mapToInt(Entry::minutes).sum());}
        }
        for(var week:weekRows.values()) week.put("varianceMinutes",((Number)week.get("reportedMinutes")).intValue()-((Number)week.get("plannedMinutes")).intValue());
        int reported=submitted.values().stream().mapToInt(r->r.entries().stream().mapToInt(Entry::minutes).sum()).sum();
        var totals=Map.of("studyDays",days.size(),"completeDays",completedDays,"attendanceDays",attendance.size(),
            "reportedMinutes",reported,"focusSeconds",total(focus.values()),"plannedMinutes",blocks.stream().mapToInt(Block::minutes).sum(),
            "reportDays",submitted.size(),"projectCount",projectNumber);
        return Map.of("days",days,"phases",phases,"projects",projects,"totals",totals,"weeks",new ArrayList<>(weekRows.values()),
            "portfolio",Map.of("notStarted",notStarted,"inProgress",inProgress,"blocked",blocked,"done",done,"practice",practice));
    }
    private static boolean hasModuleParent(Node node,Map<String,Node> nodes) {
        var visited=new HashSet<String>();Node current=nodes.get(node.parent());
        while(current!=null&&visited.add(current.id())) { if(current.kind().equals("MODULE"))return true;current=nodes.get(current.parent()); }
        return false;
    }
    private static void add(Map<String,Object> row,String key,int value) {row.put(key,((Number)row.get(key)).intValue()+value);}
}

package com.veloce.study;

import java.time.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspaces/{w}/study")
public class StudyViewApi {
    private final StudyStore s;
    StudyViewApi(StudyStore store) { this.s=store; }
    @GetMapping("/view")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    @SuppressWarnings("unchecked")
    public Map<String,Object> view(@AuthenticationPrincipal UUID owner,@PathVariable UUID w) {
        var workspace=s.ws.owned(owner,w);var roadmap=s.roadmap(workspace);
        var title=s.ws.db.queryForObject("SELECT title FROM roadmap_versions WHERE workspace_id=? AND id=?",String.class,w,roadmap);
        var nodes=s.ws.db.query("SELECT node_id,parent_id,title,kind,ordinal FROM roadmap_nodes WHERE workspace_id=? AND roadmap_id=? ORDER BY ordinal",
            (r,n)->new StudyView.Node(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getInt(5)),w,roadmap);
        var blocks=s.ws.db.query("""
            SELECT x.study_date,x.node_id,sum(x.minutes)::int,count(*)::int,count(c.session_id)::int,min(x.starts_at) AS first_start
            FROM schedule_slots x LEFT JOIN session_completions c ON c.session_id=x.id
            WHERE x.workspace_id=? AND x.roadmap_id=? AND x.kind='STUDY' AND (NOT x.cancelled OR c.session_id IS NOT NULL)
              AND (x.plan_id=? OR c.session_id IS NOT NULL OR (x.ends_at<=? AND EXISTS (
                SELECT 1 FROM study_records work WHERE work.workspace_id=x.workspace_id AND work.roadmap_id=x.roadmap_id
                  AND work.study_date=x.study_date AND work.node_id=x.node_id AND work.record_type='WORK')))
            GROUP BY x.study_date,x.node_id ORDER BY x.study_date,first_start
            """,(r,n)->new StudyView.Block(r.getObject(1,LocalDate.class),r.getString(2),r.getInt(3),r.getInt(4),r.getInt(5)),
            w,roadmap,workspace.activePlanId(),s.clock.instant().atOffset(ZoneOffset.UTC));
        var work=s.ws.db.query("""
            SELECT study_date,node_id,data->>'status',(data->>'actualMinutes')::int,version
            FROM study_records WHERE workspace_id=? AND roadmap_id=? AND record_type='WORK'
            """,(r,n)->new StudyView.Work(r.getObject(1,LocalDate.class),r.getString(2),r.getString(3),r.getInt(4),r.getLong(5)),w,roadmap);
        var snapshots=s.ws.db.query("""
            WITH latest AS (
              SELECT DISTINCT ON(study_date) study_date,version,data FROM study_reports
              WHERE workspace_id=? AND roadmap_id=? ORDER BY study_date,version DESC
            )
            SELECT l.study_date,l.version,e->>'nodeId',e->'data'->>'status',(e->'data'->>'actualMinutes')::int,(e->>'version')::bigint
            FROM latest l CROSS JOIN LATERAL jsonb_array_elements(l.data->'entries') e
            ORDER BY l.study_date
            """,(r,n)->new Object[]{r.getObject(1,LocalDate.class),r.getLong(2),new StudyView.Entry(r.getString(3),r.getString(4),r.getInt(5),r.getLong(6))},w,roadmap);
        var reports=new LinkedHashMap<LocalDate,StudyView.Report>();
        for(var row:snapshots) {
            var report=reports.computeIfAbsent((LocalDate)row[0],d->new StudyView.Report(d,(Long)row[1],new ArrayList<>()));
            report.entries().add((StudyView.Entry)row[2]);
        }
        var attendance=new HashSet<>(s.ws.db.queryForList("SELECT DISTINCT study_date FROM study_presence WHERE workspace_id=?",LocalDate.class,w));
        String zone=s.zone(workspace).getId();
        var focus=new LinkedHashMap<LocalDate,Integer>();
        s.ws.db.query("""
            SELECT d.day::date,coalesce(sum(greatest(0,extract(epoch FROM
              least(coalesce(f.ended_at,now()),(d.day+interval '1 day') AT TIME ZONE ?)
              -greatest(f.started_at,d.day AT TIME ZONE ?)))),0)::int AS seconds
            FROM focus_sessions f CROSS JOIN LATERAL generate_series(
              date_trunc('day',f.started_at AT TIME ZONE ?),date_trunc('day',coalesce(f.ended_at,now()) AT TIME ZONE ?),interval '1 day') d(day)
            WHERE f.workspace_id=?
            GROUP BY d.day::date ORDER BY d.day::date
            """,r->{focus.put(r.getObject(1,LocalDate.class),r.getInt(2));},zone,zone,zone,zone,w);
        var sections=s.ws.db.query("""
            SELECT node_id,split_part(record_key,'/',2),data->>'status'
            FROM study_records WHERE workspace_id=? AND roadmap_id=? AND record_type='PROJECT'
            """,(r,n)->new StudyView.Section(r.getString(1),r.getString(2),r.getString(3)),w,roadmap);
        var practice=new LinkedHashMap<String,Integer>();
        s.ws.db.query("SELECT data->>'status',count(*)::int FROM study_records WHERE workspace_id=? AND roadmap_id=? AND record_type='PRACTICE' GROUP BY data->>'status'",
            r->{practice.put(r.getString(1),r.getInt(2));},w,roadmap);
        var result=new LinkedHashMap<>(StudyView.build(title,nodes,blocks,work,new ArrayList<>(reports.values()),attendance,focus,sections,practice));
        var catalog=new ProjectCatalog(s).list(workspace,s.today(workspace));
        var projectViews=new LinkedHashMap<String,Object>();
        for(var project:catalog) projectViews.put(project.id(),Map.of(
            "number",project.number(),"completedSections",project.completedSections(),"status",project.status(),"sections",project.sections()));
        result.put("projects",projectViews);
        var totals=new LinkedHashMap<>((Map<String,Object>)result.get("totals"));
        totals.put("projectCount",catalog.size());result.put("totals",totals);
        var portfolio=new LinkedHashMap<>((Map<String,Object>)result.get("portfolio"));
        portfolio.put("done",(int)catalog.stream().filter(p->p.status().equals("DONE")).count());
        portfolio.put("blocked",(int)catalog.stream().filter(p->p.status().equals("BLOCKED")).count());
        portfolio.put("inProgress",(int)catalog.stream().filter(p->p.status().equals("IN_PROGRESS")).count());
        portfolio.put("notStarted",(int)catalog.stream().filter(p->p.status().equals("NOT_STARTED")).count());
        result.put("portfolio",portfolio);
        result.put("title",title);result.put("roadmapId",roadmap);result.put("planId",workspace.activePlanId());result.put("zone",zone);result.put("serverTime",s.clock.instant());return result;
    }
}

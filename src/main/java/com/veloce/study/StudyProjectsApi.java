package com.veloce.study;

import com.veloce.study.ProjectCatalog.Project;
import com.veloce.common.Problem;
import com.veloce.workspace.WorkspaceStore.Workspace;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspaces/{w}/study/projects")
public class StudyProjectsApi {
    private static final int PAGE_SIZE=60;
    private static final Set<String> FILTERS=Set.of("ALL","PENDING","DONE","TODAY","NOT_STARTED","IN_PROGRESS","BLOCKED");
    private final StudyStore s;
    StudyProjectsApi(StudyStore store) { this.s=store; }

    public record Overview(int total,int completed,int pending,int todayTotal,int todayPending,LocalDate date,String zone) {}
    public record Catalog(Overview overview,List<Project> projects,int matching,boolean hasMore) {}

    static Overview overview(List<Project> projects,LocalDate today,String zone) {
        int completed=0,todayTotal=0,todayPending=0;
        for (var project:projects) {
            if (project.status().equals("DONE")) completed++;
            if (project.scheduledToday()) {
                todayTotal++;
                if (!project.status().equals("DONE")) todayPending++;
            }
        }
        return new Overview(projects.size(),completed,projects.size()-completed,todayTotal,todayPending,today,zone);
    }
    static boolean matches(Project project,String query,String status) {
        boolean accepted=switch(status) {
            case "ALL" -> true;
            case "PENDING" -> !project.status().equals("DONE");
            case "TODAY" -> project.scheduledToday()&&!project.status().equals("DONE");
            default -> project.status().equals(status);
        };
        if (!accepted) return false;
        String searchable=project.id()+" P"+String.format(Locale.ROOT,"%02d",project.number())
            +" "+project.externalId()+" "+project.title()+" "+project.outcome()+" "+project.stack()+" "+project.level()+" "+project.resources();
        return searchable.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT).strip());
    }
    @GetMapping
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    Catalog catalog(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,
        @RequestParam(defaultValue="") String query,@RequestParam(defaultValue="ALL") String status,
        @RequestParam(defaultValue="0") int page) {
        if (query.length()>120||!FILTERS.contains(status)||page<0||page>10000)
            throw Problem.invalid("Choose a valid project search, status and page.");
        var workspace=s.ws.owned(owner,w);
        var today=s.today(workspace);
        var all=new ProjectCatalog(s).list(workspace,today);
        var visible=all.stream().filter(project->matches(project,query,status)).toList();
        int start=Math.min(page*PAGE_SIZE,visible.size()),end=Math.min(start+PAGE_SIZE,visible.size());
        return new Catalog(overview(all,today,s.zone(workspace).getId()),
            List.copyOf(visible.subList(start,end)),visible.size(),end<visible.size());
    }
    @GetMapping("/{node}")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    Project project(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable String node) {
        if (node.isBlank()||node.length()>80) throw Problem.invalid("Choose a valid project.");
        var workspace=s.ws.owned(owner,w);
        return new ProjectCatalog(s).find(workspace,node);
    }
}


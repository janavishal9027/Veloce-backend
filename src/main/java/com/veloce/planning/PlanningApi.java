package com.veloce.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloce.common.*;
import java.time.ZoneId;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class PlanningApi {
    private final PlanService service;
    private final Limits limits;
    PlanningApi(PlanService service,Limits limits) { this.service=service; this.limits=limits; }

    @GetMapping("/timezones")
    List<String> zones() { return ZoneId.getAvailableZoneIds().stream().sorted().toList(); }

    @PostMapping("/workspaces/{w}/plan-previews")
    PlanService.PreviewView preview(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@RequestBody JsonNode input) {
        limits.check("preview:"+owner,45,60);
        return service.preview(owner,w,input,false);
    }
    @PostMapping({"/workspaces/{w}/reschedule-previews","/workspaces/{w}/resume-preview"})
    PlanService.PreviewView reschedule(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@RequestBody JsonNode input) {
        limits.check("preview:"+owner,45,60);
        return service.preview(owner,w,input,true);
    }
    @GetMapping("/workspaces/{w}/plan-previews/{p}")
    PlanService.PreviewView preview(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable UUID p,
                                   @RequestParam(defaultValue="0") int page) { return service.getPreview(owner,w,p,page); }

    @PostMapping("/workspaces/{w}/generation-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    PlanService.Job queue(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@RequestBody JsonNode input,
                          @RequestHeader(value="Idempotency-Key",required=false) String key) {
        limits.check("generate:"+owner,30,60);
        UUID preview;
        try { preview=UUID.fromString(Json.text(input,"previewId",36,true)); }
        catch (IllegalArgumentException e) { throw Problem.invalid("Choose a valid preview."); }
        return service.queue(owner,w,preview,Json.revision(input),key);
    }
    @GetMapping("/workspaces/{w}/generation-jobs/{j}")
    PlanService.Job status(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable UUID j) {
        return service.status(owner,w,j);
    }

    @PostMapping("/workspaces/{w}/pause")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void pause(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@RequestBody JsonNode input) {
        service.lifecycle(owner,w,Json.revision(input),"PAUSED");
    }
    @PostMapping("/workspaces/{w}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@RequestBody JsonNode input) {
        service.lifecycle(owner,w,Json.revision(input),"ARCHIVED");
    }
}

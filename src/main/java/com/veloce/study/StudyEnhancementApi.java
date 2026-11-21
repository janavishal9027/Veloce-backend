package com.veloce.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloce.common.Limits;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/study/ai")
class StudyEnhancementApi {
    private final StudyStore study;
    private final StudyPreferences preferences;
    private final PreparationAi ai;
    private final Limits limits;

    StudyEnhancementApi(StudyStore study,StudyPreferences preferences,PreparationAi ai,Limits limits) {
        this.study=study;
        this.preferences=preferences;
        this.ai=ai;
        this.limits=limits;
    }

    @GetMapping
    ObjectNode settings(@AuthenticationPrincipal UUID owner,@PathVariable UUID workspaceId) {
        study.ws.owned(owner,workspaceId);
        return ai.settings(workspaceId);
    }

    @PutMapping
    ObjectNode save(@AuthenticationPrincipal UUID owner,@PathVariable UUID workspaceId,@RequestBody JsonNode input) {
        preferences.saveEnhancement(owner,workspaceId,input);
        return ai.settings(workspaceId);
    }

    @GetMapping("/models")
    FreeAiModels.Catalog models(@AuthenticationPrincipal UUID owner,@PathVariable UUID workspaceId,
        @RequestParam String provider,@RequestParam(defaultValue="false") boolean refresh) {
        study.ws.owned(owner,workspaceId);
        limits.check("free-model-catalog:"+owner,30,300);
        return ai.catalog(workspaceId,EnhancementProvider.parse(provider),refresh);
    }
}

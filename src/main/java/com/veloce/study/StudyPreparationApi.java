package com.veloce.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloce.common.*;
import com.veloce.roadmap.Roadmap.Resource;
import com.veloce.study.PreparationGuide.Activity;
import com.veloce.study.PreparationGuide.Chart;
import com.veloce.workspace.WorkspaceStore.Workspace;
import java.time.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/study/preparation")
public class StudyPreparationApi {
    private static final Duration PREVIEW_LIFETIME = Duration.ofMinutes(20);
    private final StudyStore study;
    private final PreparationAi ai;
    private final Limits limits;
    private final Map<UUID, Pending> previews = new LinkedHashMap<>();

    StudyPreparationApi(StudyStore study, PreparationAi ai, Limits limits) {
        this.study = study;
        this.ai = ai;
        this.limits = limits;
    }

    record Saved(UUID id, long version, String fingerprint, Chart chart) {}
    record Day(LocalDate date, List<Activity> activities, int plannedMinutes, Saved kept,
               boolean keptFromEarlierPlan, boolean aiConfigured, boolean readOnly) {}
    record Preview(UUID id, Chart chart, Instant expiresAt) {}
    private record Pending(UUID owner, UUID workspace, UUID roadmap, LocalDate date, String activity,
                           String fingerprint, long expectedVersion, Preview preview) {}

    @GetMapping
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Day day(@AuthenticationPrincipal UUID owner, @PathVariable UUID workspaceId, @RequestParam String date) {
        var workspace = study.ws.owned(owner, workspaceId);
        var roadmap = study.roadmap(workspace);
        var selected = StudyRules.date(date);
        return dayView(workspace, roadmap, selected);
    }

    @PostMapping("/preview")
    Preview preview(@AuthenticationPrincipal UUID owner, @PathVariable UUID workspaceId, @RequestBody JsonNode input) {
        var workspace = study.ws.owned(owner, workspaceId);
        study.writable(workspace);
        var roadmap = study.roadmap(workspace);
        var date = StudyRules.date(Json.text(input, "date", 10, true));
        String activity = Json.text(input, "activityId", 80, false);
        String mode = Json.text(input, "mode", 12, true);
        if (!Set.of("ROADMAP", "AI").contains(mode)) throw Problem.invalid("Choose AI Enhance or a roadmap guide in the updated application.");
        limits.check("preparation-" + mode + ":" + owner, mode.equals("AI") ? 30 : 40, 300);
        var allActivities = activities(workspace, roadmap, date);
        var selected = allActivities.stream().filter(row -> activity.isEmpty() || row.id().equals(activity)).toList();
        if (selected.isEmpty()) throw Problem.invalid("Choose a topic scheduled for this study day.");
        var saved = kept(workspaceId, roadmap, date);
        boolean deepen = input.path("more").asBoolean(false);
        Chart guide = PreparationGuide.outline(selected, deepen);
        if (mode.equals("AI")) {
            UUID previous = input.hasNonNull("previousId") ? previewId(input) : null;
            Chart earlier = null;
            if (previous != null) {
                var prior = pending(previous, owner, workspaceId, roadmap, date);
                if (!prior.activity().equals(activity)) throw Problem.invalid("Choose the same topic to extend a chart.");
                earlier = prior.preview().chart();
            } else if (deepen && saved != null && saved.fingerprint().equals(fingerprint(allActivities))) {
                earlier = saved.chart();
            }
            guide = ai.enhance(owner, workspaceId, guide, earlier);
        }
        if (study.json.write(guide).length() > 170_000) throw Problem.invalid("Choose one topic to prepare at a time.");
        var response = new Preview(UUID.randomUUID(), guide, study.clock.instant().plus(PREVIEW_LIFETIME));
        remember(new Pending(owner, workspaceId, roadmap, date, activity, fingerprint(allActivities),
            saved == null ? 0 : saved.version(), response));
        return response;
    }

    @PostMapping("/{id}/keep")
    @Transactional
    Day keep(@AuthenticationPrincipal UUID owner, @PathVariable UUID workspaceId, @PathVariable UUID id,
             @RequestBody JsonNode input) {
        var workspace = study.ws.owned(owner, workspaceId, true);
        study.writable(workspace);
        var roadmap = study.roadmap(workspace);
        var date = StudyRules.date(Json.text(input, "date", 10, true));
        var current = dayView(workspace, roadmap, date);
        var saved = current.kept();
        if (saved != null && saved.id().equals(id)) return current;
        var pending = pending(id, owner, workspaceId, roadmap, date);
        long version = saved == null ? 0 : saved.version();
        if (version != pending.expectedVersion()) {
            throw Problem.conflict("This day's preparation was saved in another view. Reload before replacing it.");
        }
        if (!pending.fingerprint().equals(fingerprint(current.activities()))) {
            throw Problem.conflict("The day's topics or study time changed. Make a new chart before keeping it.");
        }
        study.ws.db.update("""
            INSERT INTO study_day_enhancements(id,workspace_id,roadmap_id,study_date,source_fingerprint,plan)
            VALUES(?,?,?,?,?,?::jsonb)
            """, id, workspaceId, roadmap, date, pending.fingerprint(), study.json.write(pending.preview().chart()));
        study.ws.db.update("""
            INSERT INTO study_day_preparation(workspace_id,roadmap_id,study_date,version,enhancement_id)
            VALUES(?,?,?,?,?) ON CONFLICT(workspace_id,roadmap_id,study_date)
            DO UPDATE SET version=excluded.version,enhancement_id=excluded.enhancement_id,updated_at=now()
            """, workspaceId, roadmap, date, version + 1, id);
        return dayView(workspace, roadmap, date);
    }

    private Day dayView(Workspace workspace, UUID roadmap, LocalDate date) {
        var activities = activities(workspace, roadmap, date);
        var saved = kept(workspace.id(), roadmap, date);
        return new Day(date, activities, activities.stream().mapToInt(Activity::minutes).sum(), saved,
            saved != null && !saved.fingerprint().equals(fingerprint(activities)), ai.configured(workspace.id()),
            workspace.status().equals("ARCHIVED"));
    }

    private List<Activity> activities(Workspace workspace, UUID roadmap, LocalDate date) {
        return study.ws.db.query("""
            SELECT n.node_id,n.title,n.kind,n.outcome,n.resources,sum(x.minutes)::int AS minutes
            FROM schedule_slots x JOIN roadmap_nodes n USING(workspace_id,roadmap_id,node_id)
            LEFT JOIN session_completions c ON c.session_id=x.id
            WHERE x.workspace_id=? AND x.roadmap_id=? AND x.study_date=? AND x.kind='STUDY'
              AND (c.session_id IS NOT NULL OR NOT x.cancelled AND (x.plan_id=? OR (x.ends_at<=? AND EXISTS (
                SELECT 1 FROM study_records work WHERE work.workspace_id=x.workspace_id AND work.roadmap_id=x.roadmap_id
                  AND work.study_date=x.study_date AND work.node_id=x.node_id AND work.record_type='WORK'))))
            GROUP BY n.node_id,n.title,n.kind,n.outcome,n.resources
            ORDER BY min(x.starts_at),n.node_id
            """, (row, index) -> new Activity(row.getString("node_id"), row.getString("title"), row.getString("kind"),
                row.getString("outcome"), row.getInt("minutes"),
                List.of(study.json.as(study.json.read(row.getString("resources")), Resource[].class))),
            workspace.id(), roadmap, date, workspace.activePlanId(), study.clock.instant().atOffset(ZoneOffset.UTC));
    }

    private Saved kept(UUID workspace, UUID roadmap, LocalDate date) {
        return study.ws.db.query("""
            SELECT e.id,p.version,e.source_fingerprint,e.plan FROM study_day_preparation p
            JOIN study_day_enhancements e ON e.id=p.enhancement_id
            WHERE p.workspace_id=? AND p.roadmap_id=? AND p.study_date=?
            """, (row, index) -> new Saved(row.getObject("id", UUID.class), row.getLong("version"),
            row.getString("source_fingerprint"), study.json.as(study.json.read(row.getString("plan")), Chart.class)),
            workspace, roadmap, date).stream().findFirst().orElse(null);
    }

    private String fingerprint(List<Activity> activities) {
        return Json.sha256(study.json.write(activities));
    }

    private static UUID previewId(JsonNode input) {
        try {
            return UUID.fromString(Json.text(input, "previousId", 36, true));
        } catch (IllegalArgumentException e) {
            throw Problem.invalid("Choose a valid preparation preview.");
        }
    }

    // Unsaved charts stay in memory until Keep. Entries expire and the cache has a fixed size.
    private synchronized void remember(Pending pending) {
        expirePreviews();
        if (previews.size() >= 96) previews.remove(previews.keySet().iterator().next());
        previews.put(pending.preview().id(), pending);
    }

    private synchronized Pending pending(UUID id, UUID owner, UUID workspace, UUID roadmap, LocalDate date) {
        expirePreviews();
        var pending = previews.get(id);
        if (pending == null || !pending.owner().equals(owner) || !pending.workspace().equals(workspace)
            || !pending.roadmap().equals(roadmap) || !pending.date().equals(date)) {
            throw new Problem(404, "PREPARATION_PREVIEW_UNAVAILABLE", "This preview is unavailable. Make a new chart before keeping it.");
        }
        return pending;
    }

    private void expirePreviews() {
        Instant now = study.clock.instant();
        previews.values().removeIf(value -> !value.preview().expiresAt().isAfter(now));
    }
}

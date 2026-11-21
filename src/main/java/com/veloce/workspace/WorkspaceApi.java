package com.veloce.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloce.common.*;
import com.veloce.planning.Planner.Settings;
import com.veloce.integration.ReminderRules;
import java.time.Clock;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceApi {
    private final WorkspaceStore store;
    private final Clock clock;
    WorkspaceApi(WorkspaceStore store, Clock clock) { this.store=store; this.clock=clock; }

    public record Summary(UUID id,String name,String industry,String targetRole,String status,long revision,UUID activePlanId,
                          int step,Integer completedMinutes,Integer effortMinutes,String nextAt,String completionDate) {}
    public record Draft(Summary workspace,JsonNode data,UUID roadmapId,UUID sourceId) {}

    @GetMapping
    List<Summary> list(@AuthenticationPrincipal UUID owner,@RequestParam(defaultValue="0") int page) {
        if (page<0 || page>10000) throw Problem.invalid("Choose a valid page.");
        return store.all(owner,page,30).stream().map(this::summary).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Draft create(@AuthenticationPrincipal UUID owner,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        String request=Json.key(key);
        UUID id=UUID.randomUUID();
        store.db.update("""
            INSERT INTO workspaces(id,owner_id,name,create_key) VALUES(?,?,'Untitled workspace',?)
            ON CONFLICT(owner_id,create_key) DO NOTHING
            """,id,owner,request);
        UUID workspace=store.db.queryForObject("SELECT id FROM workspaces WHERE owner_id=? AND create_key=?",UUID.class,owner,request);
        store.db.update("INSERT INTO onboarding_drafts(workspace_id) VALUES(?) ON CONFLICT DO NOTHING",workspace);
        return draft(store.owned(owner,workspace));
    }

    @GetMapping("/{id}")
    Summary get(@AuthenticationPrincipal UUID owner,@PathVariable UUID id) { return summary(store.owned(owner,id)); }

    @PatchMapping("/{id}")
    @Transactional
    Summary rename(@AuthenticationPrincipal UUID owner,@PathVariable UUID id,@RequestBody JsonNode input) {
        var w=store.owned(owner,id,true);
        store.expect(w,Json.revision(input));
        if (w.status().equals("ARCHIVED")) throw Problem.conflict("Archived workspaces are read only.");
        String name=Json.text(input,"name",120,true);
        store.db.update("UPDATE workspaces SET name=? WHERE id=?",name,id);
        store.changed(id);
        if (w.activePlanId()!=null) store.integrationChanged(owner,id,"workspace-renamed");
        return summary(store.owned(owner,id));
    }

    @GetMapping("/{id}/onboarding")
    Draft onboarding(@AuthenticationPrincipal UUID owner,@PathVariable UUID id) { return draft(store.owned(owner,id)); }

    @PutMapping("/{id}/settings")
    @Transactional
    Draft settings(@AuthenticationPrincipal UUID owner,@PathVariable UUID id,@RequestBody JsonNode input) {
        var w=store.owned(owner,id,true);
        store.draft(w);
        store.expect(w,Json.revision(input));
        if (w.roadmapId()==null || w.step()<4) throw Problem.invalid("Confirm the roadmap before editing preparation and timetable.");
        var preparation=input.path("preparation");
        var timetable=input.path("timetable");
        if (!preparation.isObject() || !timetable.isObject() || store.json.write(input).length()>70_000)
            throw Problem.invalid("Enter preparation and timetable settings.");
        if (!preparation.path("zoneConfirmed").asBoolean(false)) throw Problem.invalid("Confirm the timezone for this plan.");
        Settings.checkDailyHoursChoice(preparation,w.data().path("preparation"));
        ObjectNode data=w.data().deepCopy();
        data.set("preparation",preparation); data.set("timetable",timetable);
        Settings.from(data,clock,false);
        ReminderRules.from(timetable.path("reminders"),store.json);
        store.db.update("UPDATE onboarding_drafts SET data=?::jsonb,step=6 WHERE workspace_id=?",store.json.write(data),id);
        store.changed(id);
        return draft(store.owned(owner,id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@AuthenticationPrincipal UUID owner,@PathVariable UUID id,@RequestBody JsonNode input) {
        store.accountRevision(owner,true);
        var w=store.owned(owner,id,true);
        store.expect(w,Json.revision(input));
        // Devices retain only the identifiers needed to remove Veloce-owned future projections.
        store.db.update("""
            INSERT INTO workspace_device_cleanup(owner_id,installation_id,workspace_id,calendar_writer,reminder_writer)
            SELECT owner_id,calendar_installation,workspace_id,true,false FROM integration_preferences
            WHERE workspace_id=? AND calendar_installation IS NOT NULL
            ON CONFLICT(owner_id,installation_id,workspace_id) DO UPDATE SET calendar_writer=true
            """,id);
        store.db.update("""
            INSERT INTO workspace_device_cleanup(owner_id,installation_id,workspace_id,calendar_writer,reminder_writer)
            SELECT owner_id,reminder_installation,workspace_id,false,true FROM integration_preferences
            WHERE workspace_id=? AND reminder_installation IS NOT NULL
            ON CONFLICT(owner_id,installation_id,workspace_id) DO UPDATE SET reminder_writer=true
            """,id);
        store.db.update("UPDATE workspaces SET active_plan_id=NULL WHERE id=?",id);
        for (String table:List.of("onboarding_drafts","generation_jobs","focus_sessions","session_completions",
            "calendar_event_mappings","integration_preferences","schedule_slots","plan_versions","plan_previews",
            "roadmap_dependencies","roadmap_nodes","roadmap_versions","roadmap_sources")) {
            store.db.update("DELETE FROM "+table+" WHERE workspace_id=?",id);
        }
        store.db.update("DELETE FROM workspaces WHERE id=? AND owner_id=?",id,owner);
        if (w.activePlanId()!=null)
            store.db.update("UPDATE accounts SET schedule_revision=schedule_revision+1 WHERE id=?",owner);
    }

    @PutMapping("/{id}/onboarding")
    @Transactional
    Draft save(@AuthenticationPrincipal UUID owner,@PathVariable UUID id,@RequestBody JsonNode input) {
        var w=store.owned(owner,id,true);
        store.draft(w);
        store.expect(w,Json.revision(input));
        int step=input.path("step").asInt();
        JsonNode fields=input.path("values");
        if (!input.path("step").isIntegralNumber() || !fields.isObject() || step<1 || step>5 || step>w.step() || step==3)
            throw Problem.invalid("Complete the earlier steps before saving this step.");
        boolean advance=input.path("advance").asBoolean(false);
        ObjectNode data=w.data().deepCopy();
        UUID reviewed=w.roadmapId(), source=w.sourceId();
        int unlocked=Math.max(w.step(),advance ? step+1 : step);
        if (step==1) {
            String name=Json.text(fields,"name",120,advance), industry=Json.text(fields,"industry",120,advance),
                role=Json.text(fields,"targetRole",120,advance);
            store.db.update("UPDATE workspaces SET name=?,industry=?,target_role=? WHERE id=?",
                name.isBlank()?"Untitled workspace":name,industry,role,id);
            data.put("name",name).put("industry",industry).put("targetRole",role)
                .put("experience",Json.text(fields,"experience",500,false)).put("goal",Json.text(fields,"goal",1000,false))
                .put("skillLevel",Json.text(fields,"skillLevel",30,false));
            if (name.isBlank() || industry.isBlank() || role.isBlank()) unlocked=1;
        } else if (step==2) {
            String choice=Json.text(fields,"source",30,true);
            if (!Set.of("EXISTING_UPLOAD","CHATGPT_HANDOFF").contains(choice)) throw Problem.invalid("Choose one roadmap source.");
            if (!choice.equals(data.path("source").asText())) {
                reviewed=null; source=null; unlocked=advance ? 3 : 2;
                data.remove("workbookSchedule");
            }
            data.put("source",choice);
        } else {
            if (reviewed==null) throw Problem.invalid("Confirm your roadmap first.");
            var part=step==4?"preparation":"timetable";
            if (store.json.write(fields).length()>32_000) throw Problem.invalid("Your preferences are too large.");
            boolean settingsChanged=!fields.equals(data.path(part));
            if (step==4 && (advance || fields.has("dailyMinutes")))
                Settings.checkDailyHoursChoice(fields, data.path(part));
            data.set(part, fields);
            if (advance) {
                if (step==4) {
                    var preparation=Settings.preparation(fields,clock,false);
                    if (fields.path("applyWorkbookTimetable").asBoolean()) {
                        applyWorkbookTimetable(data,preparation.dailyMinutes());
                        ((ObjectNode)fields).remove("applyWorkbookTimetable");
                        unlocked=5;
                    }
                } else {
                    Settings.from(data,clock,false);
                    ReminderRules.from(fields.path("reminders"),store.json);
                }
            }
            if (settingsChanged) unlocked=advance ? step+1 : step;
        }
        if (w.step()!=unlocked || !Objects.equals(w.roadmapId(),reviewed) || !Objects.equals(w.sourceId(),source) || !data.equals(w.data())) {
            store.db.update("UPDATE onboarding_drafts SET step=?,data=?::jsonb,roadmap_id=?,current_source_id=? WHERE workspace_id=?",
                unlocked,store.json.write(data),reviewed,source,id);
            store.changed(id);
        }
        return draft(store.owned(owner,id));
    }

    private Draft draft(WorkspaceStore.Workspace w) { return new Draft(summary(w),w.data(),w.roadmapId(),w.sourceId()); }

    private void applyWorkbookTimetable(ObjectNode data,int dailyMinutes) {
        var schedule=data.path("workbookSchedule");
        if (!schedule.isObject() || schedule.path("dailyMinutes").asInt()!=dailyMinutes)
            throw Problem.invalid("Choose the workbook's daily study time to apply its timetable.");
        var timetable=store.json.object().put("focusMinutes",50).put("breakMinutes",0).put("longBreakMinutes",0)
            .put("longBreakEvery",3);
        timetable.putArray("indivisible");
        timetable.set("workbookBlocks",schedule.path("blocks"));
        timetable.putArray("windows").addObject().put("day",0)
            .put("start",schedule.path("windowStart").asText()).put("end",schedule.path("windowEnd").asText());
        var existing=data.path("timetable").path("reminders");
        var rules=timetable.putObject("reminders").put("channel","CALENDAR")
            .put("quietStart",existing.path("quietStart").asText("22:00")).put("quietEnd",existing.path("quietEnd").asText("07:00"))
            .put("genericTitles",existing.path("genericTitles").asBoolean());
        rules.putArray("rules");
        rules.set("dayReminders",schedule.path("reminders"));
        data.set("timetable",timetable);
        Settings.from(data,clock,false);
        ReminderRules.from(rules,store.json);
    }

    private Summary summary(WorkspaceStore.Workspace w) {
        Integer completed=null, effort=null; String next=null, completion=null;
        if (w.activePlanId()!=null) {
            var row=store.db.queryForMap("""
                SELECT p.summary->>'effort' AS effort,p.summary->>'completionDate' AS completion,
                  (SELECT coalesce(sum(s.minutes),0) FROM schedule_slots s JOIN session_completions c ON c.session_id=s.id
                    WHERE s.workspace_id=p.workspace_id AND s.roadmap_id=p.roadmap_id) AS completed,
                  (SELECT min(s.starts_at) FROM schedule_slots s WHERE s.plan_id=p.id AND NOT s.cancelled AND s.kind='STUDY'
                    AND s.ends_at>now() AND NOT EXISTS(SELECT 1 FROM session_completions c WHERE c.session_id=s.id)) AS next
                FROM plan_versions p WHERE p.id=?
                """,w.activePlanId());
            effort=Integer.parseInt(row.get("effort").toString()); completed=((Number)row.get("completed")).intValue();
            Object date=row.get("next"); if (date instanceof java.sql.Timestamp timestamp) next=timestamp.toInstant().toString();
            if (row.get("completion")!=null) completion=row.get("completion").toString();
        }
        return new Summary(w.id(),w.name(),w.industry(),w.targetRole(),w.status(),w.revision(),w.activePlanId(),w.step(),completed,effort,next,completion);
    }
}

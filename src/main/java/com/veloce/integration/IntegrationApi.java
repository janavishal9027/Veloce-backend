package com.veloce.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloce.common.*;
import com.veloce.workspace.WorkspaceStore;
import java.time.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class IntegrationApi {
    private final WorkspaceStore ws;
    private final Clock clock;
    IntegrationApi(WorkspaceStore ws,Clock clock) { this.ws=ws; this.clock=clock; }

    record Preferences(UUID workspaceId,UUID calendarInstallation,String calendarId,UUID reminderInstallation,
                       JsonNode rules,long revision,LocalDate syncedThrough,LocalDate armedThrough,
                       String calendarStatus,String notificationStatus) {}
    record Event(UUID sessionId,String title,String description,Instant start,Instant end,String zone,List<Integer> offsets) {}
    record Alarm(String id,UUID workspaceId,UUID sessionId,String title,Instant at) {}
    record Projection(UUID workspaceId,long revision,String calendarId,boolean calendarWriter,boolean reminderWriter,
                      LocalDate through,List<Event> events,List<Alarm> alarms) {}

    @PostMapping("/installations/{i}")
    void installation(@AuthenticationPrincipal UUID owner,@PathVariable UUID i) {
        ws.db.update("INSERT INTO installations(id,owner_id) VALUES(?,?) ON CONFLICT DO NOTHING",i,owner);
        ownedInstallation(owner,i);
    }

    @GetMapping("/workspaces/{w}/integrations")
    Preferences preferences(@AuthenticationPrincipal UUID owner,@PathVariable UUID w) {
        ws.owned(owner,w);
        return get(w);
    }

    @PutMapping("/workspaces/{w}/reminder-rules")
    @Transactional
    Preferences rules(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@RequestBody JsonNode input) {
        var workspace=ws.owned(owner,w,true);
        if (workspace.status().equals("ARCHIVED")) throw Problem.conflict("Archived workspaces are read only.");
        var rules=ReminderRules.from(input.path("rules"),ws.json);
        UUID installation=id(input,"installationId");
        var existing=get(w);
        UUID writer=existing.reminderInstallation();
        if (installation!=null && Set.of("APP","BOTH").contains(rules.channel())) {
            ownedInstallation(owner,installation);
            if (writer!=null && !writer.equals(installation))
                throw Problem.conflict("App reminders are assigned to another phone. Disable them there before switching devices.");
            writer=installation;
        }
        ws.db.update("""
            INSERT INTO integration_preferences(workspace_id,owner_id,rules,reminder_installation) VALUES(?,?,?::jsonb,?)
            ON CONFLICT(workspace_id) DO UPDATE SET rules=excluded.rules,reminder_installation=excluded.reminder_installation
            """,w,owner,ws.json.write(rules),writer);
        ws.integrationChanged(owner,w,"reminder-rules");
        return get(w);
    }

    @PutMapping("/workspaces/{w}/calendar-bindings")
    @Transactional
    Preferences calendar(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@RequestBody JsonNode input) {
        var workspace=ws.owned(owner,w,true);
        if (workspace.status().equals("ARCHIVED")) throw Problem.conflict("Archived workspaces are read only.");
        UUID installation=id(input,"installationId");
        if (installation==null) throw Problem.invalid("Choose a calendar installation.");
        ownedInstallation(owner,installation);
        String calendar=Json.text(input,"calendarId",100,true);
        if (!calendar.matches("[0-9]{1,19}")) throw Problem.invalid("Choose a writable device calendar.");
        var existing=get(w);
        if (existing.calendarInstallation()!=null && (!existing.calendarInstallation().equals(installation)
            || !existing.calendarId().equals(calendar))) throw Problem.conflict("Disconnect the previous calendar before linking a different one.");
        ws.db.update("""
            INSERT INTO integration_preferences(workspace_id,owner_id,calendar_installation,calendar_id) VALUES(?,?,?,?)
            ON CONFLICT(workspace_id) DO UPDATE SET calendar_installation=excluded.calendar_installation,calendar_id=excluded.calendar_id
            """,w,owner,installation,calendar);
        ws.integrationChanged(owner,w,"calendar-linked");
        return get(w);
    }

    @DeleteMapping("/workspaces/{w}/calendar-bindings/{i}")
    @Transactional
    Preferences disconnect(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable UUID i) {
        ws.owned(owner,w,true); ownedInstallation(owner,i);
        var existing=get(w);
        if (existing.calendarInstallation()!=null && !existing.calendarInstallation().equals(i))
            throw Problem.conflict("Disconnect this calendar on its writing phone.");
        ws.db.update("""
            UPDATE integration_preferences SET calendar_installation=NULL,calendar_id=NULL,synced_through=NULL,calendar_status=NULL
            WHERE workspace_id=? AND calendar_installation=?
            """,w,i);
        ws.db.update("DELETE FROM calendar_event_mappings WHERE workspace_id=? AND installation_id=?",w,i);
        ws.integrationChanged(owner,w,"calendar-disconnected");
        return get(w);
    }

    @DeleteMapping("/workspaces/{w}/reminder-installation/{i}")
    @Transactional
    Preferences releaseReminders(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable UUID i) {
        ws.owned(owner,w,true); ownedInstallation(owner,i);
        var existing=get(w);
        if (existing.reminderInstallation()!=null && !existing.reminderInstallation().equals(i))
            throw Problem.conflict("Disconnect app notifications on the phone that schedules them.");
        ws.db.update("""
            UPDATE integration_preferences SET reminder_installation=NULL,armed_through=NULL,notification_status=NULL
            WHERE workspace_id=? AND reminder_installation=?
            """,w,i);
        ws.integrationChanged(owner,w,"reminders-released");
        return get(w);
    }

    record WorkspaceCleanup(UUID workspaceId,boolean calendarWriter,boolean reminderWriter) {}

    @GetMapping("/installations/{i}/workspace-deletions")
    List<WorkspaceCleanup> deletions(@AuthenticationPrincipal UUID owner,@PathVariable UUID i) {
        ownedInstallation(owner,i);
        return ws.db.query("""
            SELECT workspace_id,calendar_writer,reminder_writer FROM workspace_device_cleanup
            WHERE owner_id=? AND installation_id=? ORDER BY created_at,workspace_id LIMIT 50
            """,(r,n)->new WorkspaceCleanup(r.getObject(1,UUID.class),r.getBoolean(2),r.getBoolean(3)),owner,i);
    }

    @PostMapping("/installations/{i}/workspace-deletions/{w}/ack")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void deleted(@AuthenticationPrincipal UUID owner,@PathVariable UUID i,@PathVariable UUID w) {
        ownedInstallation(owner,i);
        ws.db.update("DELETE FROM workspace_device_cleanup WHERE owner_id=? AND installation_id=? AND workspace_id=?",owner,i,w);
    }

    @GetMapping("/installations/{i}/integration-work")
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    List<Projection> work(@AuthenticationPrincipal UUID owner,@PathVariable UUID i,
                          @RequestParam(defaultValue="0") int page) {
        ownedInstallation(owner,i);
        if (page<0 || page>10000) throw Problem.invalid("Choose a valid projection page.");
        var rows=ws.db.query("""
            SELECT p.*,w.name,w.status,w.active_plan_id,v.settings->'preparation'->>'zone' AS zone
            FROM integration_preferences p JOIN workspaces w ON w.id=p.workspace_id
            LEFT JOIN plan_versions v ON v.id=w.active_plan_id
            WHERE p.owner_id=? AND (calendar_installation=? OR reminder_installation=?) ORDER BY p.workspace_id LIMIT 8 OFFSET ?
            """,(r,n)->new Binding(map(r),r.getString("name"),r.getString("status"),r.getObject("active_plan_id",UUID.class),r.getString("zone")),owner,i,i,page*8);
        return rows.stream().map(b->projection(b,i)).toList();
    }

    private record Binding(Preferences prefs,String name,String status,UUID plan,String zone) {}
    private Projection projection(Binding b,UUID installation) {
        var p=b.prefs();
        boolean cal=installation.equals(p.calendarInstallation()),notify=installation.equals(p.reminderInstallation());
        List<Event> events=new ArrayList<>(); List<Alarm> alarms=new ArrayList<>();
        LocalDate through=null;
        if ("ACTIVE".equals(b.status()) && b.plan()!=null) {
            Instant now=clock.instant();
            ZoneId zone=ZoneId.of(b.zone());
            LocalDate today=LocalDate.now(clock.withZone(zone));
            through=today.plusDays(29);
            var rules=ReminderRules.from(p.rules(),ws.json);
            var day=new LocalDate[]{null};
            ws.db.query("""
                SELECT s.id,s.study_date,s.starts_at,s.ends_at,n.title,n.outcome,n.kind,c.session_id AS completed
                FROM schedule_slots s JOIN roadmap_nodes n USING(workspace_id,roadmap_id,node_id)
                LEFT JOIN session_completions c ON c.session_id=s.id
                WHERE s.plan_id=? AND NOT s.cancelled AND s.kind='STUDY' AND s.study_date BETWEEN ? AND ?
                ORDER BY s.starts_at,s.id
                """,r->{
                    var date=r.getObject("study_date",LocalDate.class);
                    boolean first=!date.equals(day[0]); day[0]=date;
                    var start=r.getObject("starts_at",OffsetDateTime.class).toInstant();
                    var end=r.getObject("ends_at",OffsetDateTime.class).toInstant();
                    if (!start.isAfter(now)) return;
                    var session=r.getObject("id",UUID.class);
                    String title=rules.genericTitles()?"Veloce study":"Veloce · "+b.name()+" · "+r.getString("title");
                    List<Integer> offsets=r.getObject("completed")==null?rules.offsets(first,r.getString("kind"),start,zone)
                        .stream().filter(o->start.minusSeconds(o*60L).isAfter(now)).toList():List.of();
                    if (cal) events.add(new Event(session,title,rules.genericTitles()?"":r.getString("outcome"),start,end,b.zone(),
                        Set.of("CALENDAR","BOTH").contains(rules.channel())?offsets:List.of()));
                    if (notify && Set.of("APP","BOTH").contains(rules.channel()))
                        for (int offset:offsets) alarms.add(new Alarm(session+"-"+offset,p.workspaceId(),session,title,start.minusSeconds(offset*60L)));
                },b.plan(),today,through);
        }
        return new Projection(p.workspaceId(),p.revision(),cal?p.calendarId():null,cal,notify,through,events,alarms);
    }

    @PostMapping("/installations/{i}/integration-acks")
    @Transactional
    void ack(@AuthenticationPrincipal UUID owner,@PathVariable UUID i,@RequestBody JsonNode input) {
        ownedInstallation(owner,i);
        UUID workspace=id(input,"workspaceId");
        if (workspace==null) throw Problem.invalid("Select a workspace.");
        ws.owned(owner,workspace,true);
        var p=get(workspace);
        if (p.revision()!=Json.revision(input)) throw Problem.conflict("The schedule changed. Refresh this device’s sync.");
        if (i.equals(p.calendarInstallation()) && input.has("calendarStatus")) {
            String status=status(input,"calendarStatus",Set.of("Synced","Permission required","Calendar unavailable","Failed","No active schedule"));
            LocalDate through=through(input,"syncedThrough",status.equals("Synced"));
            ws.db.update("UPDATE integration_preferences SET calendar_status=?,synced_through=? WHERE workspace_id=?",status,through,workspace);
            if (status.equals("Synced")) {
                var mappings=input.path("mappings");
                if (!mappings.isObject() || mappings.size()>20_000) throw Problem.invalid("Invalid calendar event mappings.");
                for (var m:mappings.properties()) {
                    UUID session;
                    try { session=UUID.fromString(m.getKey()); } catch (IllegalArgumentException e) { throw Problem.invalid("Invalid calendar event."); }
                    String event=m.getValue().asText();
                    if (!event.matches("[0-9]{1,19}")) throw Problem.invalid("Invalid calendar event ID.");
                    ws.db.update("""
                        INSERT INTO calendar_event_mappings(workspace_id,owner_id,installation_id,session_id,event_id,revision)
                        SELECT ?,?,?,s.id,?,? FROM schedule_slots s WHERE s.workspace_id=? AND s.id=? AND s.kind='STUDY'
                        ON CONFLICT(workspace_id,installation_id,session_id) DO UPDATE SET event_id=excluded.event_id,revision=excluded.revision
                        """,workspace,owner,i,event,p.revision(),workspace,session);
                }
                ws.db.update("""
                    DELETE FROM calendar_event_mappings m USING schedule_slots s WHERE m.session_id=s.id AND m.workspace_id=? AND
                        m.installation_id=? AND s.starts_at>=now() AND m.revision<>?
                    """,workspace,i,p.revision());
            }
        }
        if (i.equals(p.reminderInstallation()) && input.has("notificationStatus")) {
            String status=status(input,"notificationStatus",Set.of("Armed","Delivery restricted","Permission required","Failed","Off","No active schedule"));
            LocalDate through=through(input,"armedThrough",Set.of("Armed","Delivery restricted").contains(status));
            ws.db.update("UPDATE integration_preferences SET notification_status=?,armed_through=? WHERE workspace_id=?",status,through,workspace);
        }
    }

    private Preferences get(UUID workspace) {
        return ws.db.query("SELECT * FROM integration_preferences WHERE workspace_id=?",(r,n)->map(r),workspace)
            .stream().findFirst().orElse(new Preferences(workspace,null,null,null,ws.json.object().put("channel","OFF"),0,null,null,null,null));
    }
    private Preferences map(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Preferences(r.getObject("workspace_id",UUID.class),r.getObject("calendar_installation",UUID.class),r.getString("calendar_id"),
            r.getObject("reminder_installation",UUID.class),ws.json.read(r.getString("rules")),r.getLong("revision"),
            r.getObject("synced_through",LocalDate.class),r.getObject("armed_through",LocalDate.class),r.getString("calendar_status"),r.getString("notification_status"));
    }
    private void ownedInstallation(UUID owner,UUID i) {
        if (Boolean.FALSE.equals(ws.db.queryForObject("SELECT EXISTS(SELECT 1 FROM installations WHERE id=? AND owner_id=?)",Boolean.class,i,owner)))
            throw Problem.missing();
    }
    private static UUID id(JsonNode input,String field) {
        String value=Json.text(input,field,36,false);
        try { return value.isEmpty()?null:UUID.fromString(value); } catch (IllegalArgumentException e) { throw Problem.invalid("Check "+field+"."); }
    }
    private static String status(JsonNode input,String field,Set<String> allowed) {
        String value=Json.text(input,field,40,true);
        if (!allowed.contains(value)) throw Problem.invalid("Check "+field+".");
        return value;
    }
    private LocalDate through(JsonNode input,String field,boolean enabled) {
        if (!enabled) return null;
        try {
            var value=LocalDate.parse(Json.text(input,field,10,true));
            var now=LocalDate.now(clock);
            if (value.isBefore(now.minusDays(2)) || value.isAfter(now.plusDays(31))) throw Problem.invalid("Check the sync horizon.");
            return value;
        } catch (DateTimeException e) { throw Problem.invalid("Check the sync horizon."); }
    }
}

package com.veloce.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloce.common.*;
import com.veloce.workspace.WorkspaceStore;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Uses only the secondary calendar created by Veloce after explicit Google consent. */
@Service
public class GoogleCalendarService {
    private static final String SCOPE="https://www.googleapis.com/auth/calendar.app.created";
    private static final String BASE="https://www.googleapis.com/calendar/v3";
    private final WorkspaceStore ws;private final CredentialVault vault;private final Clock clock;
    private final String client,secret,redirect;private final boolean jobs;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final SecureRandom random=new SecureRandom();
    public GoogleCalendarService(WorkspaceStore ws,CredentialVault vault,Clock clock,
        @Value("${veloce.google.client-id:}") String client,@Value("${veloce.google.client-secret:}") String secret,
        @Value("${veloce.google.redirect-uri:http://localhost:8090/api/v1/google-calendar/callback}") String redirect,
        @Value("${veloce.jobs-enabled}") boolean jobs) {
        this.ws=ws;this.vault=vault;this.clock=clock;this.client=client;this.secret=secret;this.redirect=redirect;this.jobs=jobs;
    }
    boolean configured(){return !client.isBlank()&&!secret.isBlank();}
    public Map<String,Object> status(UUID owner) {
        var result=new LinkedHashMap<String,Object>();result.put("configured",configured());result.put("redirectUri",redirect);
        var rows=ws.db.query("SELECT email,status,last_error,last_sync FROM google_calendar_connections WHERE owner_id=?",
            (r,n)->{var x=new LinkedHashMap<String,Object>();x.put("email",r.getString(1));x.put("status",r.getString(2));x.put("message",r.getString(3));
                x.put("lastSync",r.getString(4));return x;},owner);
        result.put("connected",!rows.isEmpty()&&!"DISCONNECTED".equals(rows.getFirst().get("status")));
        if(!rows.isEmpty())result.putAll(rows.getFirst());
        result.put("events",ws.db.queryForObject("SELECT count(*) FROM google_calendar_events WHERE owner_id=?",Integer.class,owner));
        return result;
    }
    public Map<String,String> authorize(UUID owner) {
        if(!configured())throw new Problem(412,"GOOGLE_SETUP_REQUIRED","Google Calendar needs the server's OAuth client configuration. See docs/google-calendar-setup.md.");
        String email=ws.db.queryForObject("SELECT email FROM accounts WHERE id=?",String.class,owner);
        String state=nonce(),verifier=nonce();String challenge;
        try{challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));}
        catch(GeneralSecurityException e){throw new IllegalStateException(e);}
        ws.db.update("DELETE FROM google_calendar_authorizations WHERE owner_id=? OR expires_at<now()",owner);
        ws.db.update("INSERT INTO google_calendar_authorizations(state_hash,owner_id,verifier,expires_at) VALUES(?,?,?,?)",
            Json.sha256(state),owner,vault.seal(verifier),clock.instant().plusSeconds(600).atOffset(ZoneOffset.UTC));
        String query=form(Map.of("client_id",client,"redirect_uri",redirect,"response_type","code","scope","openid email "+SCOPE,
            "access_type","offline","prompt","consent","state",state,"login_hint",email,"code_challenge",challenge,"code_challenge_method","S256"));
        return Map.of("url","https://accounts.google.com/o/oauth2/v2/auth?"+query);
    }
    public synchronized void callback(String state,String code,String error) {
        if(state==null||state.length()>200)throw Problem.invalid("Calendar authorization expired. Connect again in Veloce.");
        var states=ws.db.query("DELETE FROM google_calendar_authorizations WHERE state_hash=? AND expires_at>now() RETURNING owner_id,verifier",
            (r,n)->Map.of("owner",r.getObject(1,UUID.class),"verifier",r.getString(2)),Json.sha256(state));
        if(states.isEmpty())throw Problem.invalid("Calendar authorization expired or was already used. Connect again in Veloce.");
        if(error!=null||code==null||code.length()>4096)throw Problem.invalid("Google Calendar permission was not granted. Your Veloce schedule is unchanged.");
        UUID owner=(UUID)states.getFirst().get("owner");
        var token=call("POST","https://oauth2.googleapis.com/token",null,form(Map.of("client_id",client,"client_secret",secret,
            "redirect_uri",redirect,"code",code,"grant_type","authorization_code","code_verifier",vault.open((String)states.getFirst().get("verifier")))),true);
        String access=Json.text(token,"access_token",8192,true),refresh=Json.text(token,"refresh_token",8192,true);
        if(!Arrays.asList(token.path("scope").asText().split(" ")).contains(SCOPE))throw Problem.invalid("Grant permission to the Veloce calendar before connecting.");
        var identity=call("GET","https://openidconnect.googleapis.com/v1/userinfo",access,null,false);
        String expected=ws.db.queryForObject("SELECT email FROM accounts WHERE id=?",String.class,owner);
        if(!identity.path("email_verified").asBoolean()||!expected.equalsIgnoreCase(identity.path("email").asText()))
            throw Problem.invalid("Connect the Google account with the same email as your Veloce account.");
        var existing=ws.db.queryForList("SELECT calendar_id FROM google_calendar_connections WHERE owner_id=?",String.class,owner);
        String calendar=existing.isEmpty()?"":existing.getFirst();
        if(!calendar.isEmpty()) {
            try {
                call("GET",BASE+"/calendars/"+encode(calendar),access,null,false);
            } catch(RemoteFailure e) {
                if(e.status!=404 && e.status!=410) throw e;
                calendar="";
            }
        }
        if(calendar.isEmpty()) {
            calendar=call("POST",BASE+"/calendars",access,
                ws.json.write(Map.of("summary","Veloce study","description","Roadmap schedules managed by Veloce")),false).path("id").asText();
        }
        if(calendar.isBlank())throw new Problem(502,"CALENDAR_FAILED","Google did not return a writable Veloce calendar.");
        if(!existing.isEmpty()&&!calendar.equals(existing.getFirst()))
            ws.db.update("DELETE FROM google_calendar_events WHERE owner_id=?",owner);
        ws.db.update("""
            INSERT INTO google_calendar_connections(owner_id,email,calendar_id,refresh_token,access_token,expires_at,status,next_attempt)
            VALUES(?,?,?,?,?,?,'PENDING',now()) ON CONFLICT(owner_id) DO UPDATE SET email=excluded.email,calendar_id=excluded.calendar_id,
              refresh_token=excluded.refresh_token,access_token=excluded.access_token,expires_at=excluded.expires_at,
              status='PENDING',last_error='',next_attempt=now()
            """,owner,expected,calendar,vault.seal(refresh),vault.seal(access),expiry(token));
    }
    public void requestSync(UUID owner) {
        ws.db.update("UPDATE google_calendar_connections SET next_attempt=now(),status='PENDING',last_error='' WHERE owner_id=? AND status<>'DISCONNECTED'",owner);
    }
    public synchronized void disconnect(UUID owner) {
        // Retained Google events belong to the user's calendar; disconnect stops all further writes.
        ws.db.update("DELETE FROM google_calendar_authorizations WHERE owner_id=?",owner);
        ws.db.update("""
            UPDATE google_calendar_connections SET status='DISCONNECTED',refresh_token='',access_token='',expires_at=now(),last_error=''
            WHERE owner_id=?
            """,owner);
    }
    private OffsetDateTime expiry(JsonNode token){return clock.instant().plusSeconds(Math.max(60,token.path("expires_in").asLong(3600))-30).atOffset(ZoneOffset.UTC);}
    private String access(UUID owner,Map<String,Object> connection) {
        var expiry=connection.get("expires_at");
        var expires=expiry instanceof OffsetDateTime d?d.toInstant():((java.sql.Timestamp)expiry).toInstant();
        if(expires.isAfter(clock.instant().plusSeconds(30)))return vault.open((String)connection.get("access_token"));
        var token=call("POST","https://oauth2.googleapis.com/token",null,form(Map.of("client_id",client,"client_secret",secret,
            "refresh_token",vault.open((String)connection.get("refresh_token")),"grant_type","refresh_token")),true);
        String access=Json.text(token,"access_token",8192,true);
        ws.db.update("UPDATE google_calendar_connections SET access_token=?,expires_at=? WHERE owner_id=?",vault.seal(access),expiry(token),owner);
        return access;
    }
    @Scheduled(fixedDelay=10000,scheduler="calendarScheduler")
    public synchronized void worker() {
        if(!jobs||!configured())return;
        var owners=ws.db.queryForList("SELECT owner_id FROM google_calendar_connections WHERE status<>'DISCONNECTED' AND next_attempt<=now() ORDER BY next_attempt LIMIT 1",UUID.class);
        if(owners.isEmpty())return;UUID owner=owners.getFirst();
        ws.db.update("UPDATE google_calendar_connections SET next_attempt=now()+interval '1 minute' WHERE owner_id=?",owner);
        try{sync(owner);}catch(Problem e){
            ws.db.update("UPDATE google_calendar_connections SET status='ACTION_REQUIRED',last_error=?,next_attempt=now()+interval '5 minutes' WHERE owner_id=?",e.getMessage(),owner);
        }catch(RuntimeException e){ws.db.update("UPDATE google_calendar_connections SET status='RETRYING',last_error='Calendar sync could not finish. Veloce will retry.',next_attempt=now()+interval '5 minutes' WHERE owner_id=?",owner);}
    }
    private void sync(UUID owner) {
        var connection=ws.db.queryForMap("SELECT * FROM google_calendar_connections WHERE owner_id=?",owner);
        String token=access(owner,connection),calendar=(String)connection.get("calendar_id");
        String path=BASE+"/calendars/"+encode(calendar)+"/events";
        var desired=calendarEvents(owner);
        var desiredIds=new HashSet<UUID>();
        desired.forEach(event->desiredIds.add(event.id()));
        var tracked=ws.db.query("""
            SELECT session_id,event_id,fingerprint FROM google_calendar_events WHERE owner_id=? AND ends_at>now()
            """,(row,index)->new TrackedEvent(row.getObject(1,UUID.class),row.getString(2),row.getString(3)),owner);
        var byId=new HashMap<UUID,TrackedEvent>();
        int writes=0;
        // A single list covers both study sessions and timed reminders that moved or were removed.
        for(var existing:tracked) {
            if(desiredIds.contains(existing.id())) {
                byId.put(existing.id(),existing);
                continue;
            }
            try {
                call("DELETE",path+"/"+existing.remoteId(),token,null,false);
            } catch(RemoteFailure e) {
                if(e.status!=404 && e.status!=410) throw e;
            }
            ws.db.update("DELETE FROM google_calendar_events WHERE owner_id=? AND session_id=?",owner,existing.id());
            if(++writes==60) break;
        }
        for(var event:desired) {
            if(writes==60) break;
            var body=event.body().get();
            String hash=Json.sha256(ws.json.write(body));
            var existing=byId.get(event.id());
            if(existing!=null && hash.equals(existing.fingerprint())) continue;
            String remoteId=eventId(owner,event.id());
            upsert(path,token,body,remoteId,existing!=null);
            ws.db.update("""
                INSERT INTO google_calendar_events(owner_id,session_id,event_id,fingerprint,ends_at) VALUES(?,?,?,?,?)
                ON CONFLICT(owner_id,session_id) DO UPDATE SET fingerprint=excluded.fingerprint,ends_at=excluded.ends_at
                """,owner,event.id(),remoteId,hash,event.end().atOffset(ZoneOffset.UTC));
            writes++;
        }
        boolean pending=writes==60;
        ws.db.update("UPDATE google_calendar_connections SET status=?,last_error='',last_sync=now(),next_attempt=now()+(? * interval '1 second') WHERE owner_id=?",
            pending?"PENDING":"SYNCED",pending?10:60,owner);
    }

    private record TrackedEvent(UUID id,String remoteId,String fingerprint) {}
    private record ScheduledEvent(UUID id,Instant start,Instant end,java.util.function.Supplier<ObjectNode> body) {}

    private List<ScheduledEvent> calendarEvents(UUID owner) {
        Instant now=clock.instant();
        var slots=ws.db.query("""
            SELECT x.id,x.workspace_id,x.starts_at,x.ends_at,x.kind,x.study_date,x.track_label,w.name,n.title,n.outcome,n.kind AS node_kind,
              coalesce(p.settings->'preparation'->>'zone','UTC') AS zone,coalesce(i.rules,'{}')::text AS rules
            FROM schedule_slots x JOIN workspaces w ON w.id=x.workspace_id JOIN plan_versions p ON p.id=x.plan_id
            LEFT JOIN roadmap_nodes n ON n.workspace_id=x.workspace_id AND n.roadmap_id=x.roadmap_id AND n.node_id=x.node_id
            LEFT JOIN integration_preferences i ON i.workspace_id=x.workspace_id
            WHERE w.owner_id=? AND w.status='ACTIVE' AND x.plan_id=w.active_plan_id AND NOT x.cancelled
              AND x.study_date >= (now() AT TIME ZONE coalesce(p.settings->'preparation'->>'zone','UTC'))::date
            ORDER BY x.starts_at,x.id
            """,(r,n)->{var row=new LinkedHashMap<String,Object>();for(String key:List.of("id","workspace_id","kind","track_label","name","title","outcome","node_kind","zone","rules"))row.put(key,r.getObject(key));
                row.put("study_date",r.getObject("study_date",LocalDate.class));
                row.put("starts_at",r.getObject("starts_at",OffsetDateTime.class));row.put("ends_at",r.getObject("ends_at",OffsetDateTime.class));return row;},owner);
        var first=new HashSet<String>();
        var desired=new ArrayList<ScheduledEvent>();
        for(var slot:slots) {
            boolean study=slot.get("kind").equals("STUDY");
            boolean dayFirst=study&&first.add(slot.get("workspace_id")+"/"+slot.get("study_date"));
            var start=((OffsetDateTime)slot.get("starts_at")).toInstant();
            var end=((OffsetDateTime)slot.get("ends_at")).toInstant();
            if(end.isAfter(now)) desired.add(new ScheduledEvent((UUID)slot.get("id"),start,end,()->event(ws.json,slot,dayFirst)));
            if(dayFirst) {
                var rules=ReminderRules.from(ws.json.read(slot.get("rules").toString()),ws.json);
                String zone=slot.get("zone").toString();
                var reminders=rules.calendarReminders((UUID)slot.get("workspace_id"),(LocalDate)slot.get("study_date"),start,ZoneId.of(zone));
                for(var reminder:reminders) {
                    var until=reminder.at().plusSeconds(60);
                    if(until.isAfter(now)) desired.add(new ScheduledEvent(reminder.id(),reminder.at(),until,
                        ()->reminderEvent(ws.json,reminder,zone)));
                }
            }
        }
        desired.sort(Comparator.comparing(ScheduledEvent::start).thenComparing(ScheduledEvent::id));
        return desired;
    }

    private void upsert(String path,String token,ObjectNode body,String id,boolean tracked) {
        if(tracked) {
            try {
                call("PATCH",path+"/"+id,token,ws.json.write(body),false);
                return;
            } catch(RemoteFailure e) {
                if(e.status!=404 && e.status!=410) throw e;
            }
        }
        body.put("id",id);
        try {
            call("POST",path,token,ws.json.write(body),false);
        } catch(RemoteFailure e) {
            if(e.status!=409) throw e;
            body.remove("id");
            call("PATCH",path+"/"+id,token,ws.json.write(body),false);
        }
    }
    static String eventId(UUID owner,UUID session){return "e"+Json.sha256(owner+"/"+session);}
    static ObjectNode event(Json json,Map<String,Object> slot,boolean first) {
        var rules=ReminderRules.from(json.read(slot.get("rules").toString()),json);boolean study=slot.get("kind").equals("STUDY");
        String title=study?Objects.toString(slot.get("title"),"Study"):"Break";
        if(study && slot.get("track_label")!=null) title=slot.get("track_label")+" · "+title;
        var body=json.object().put("summary",rules.genericTitles()?"Veloce study":slot.get("name")+" · "+title)
            .put("description",rules.genericTitles()?"Scheduled by Veloce.":Objects.toString(slot.get("outcome"),"Scheduled break."));
        String zone=Objects.toString(slot.get("zone"),"UTC");var start=((OffsetDateTime)slot.get("starts_at")).toInstant();
        body.putObject("start").put("dateTime",start.toString()).put("timeZone",zone);
        body.putObject("end").put("dateTime",((OffsetDateTime)slot.get("ends_at")).toInstant().toString()).put("timeZone",zone);
        var reminders=body.putObject("reminders");reminders.put("useDefault",false);var overrides=reminders.putArray("overrides");
        if(study&&Set.of("CALENDAR","BOTH").contains(rules.channel()))
            for(int offset:rules.offsets(first,Objects.toString(slot.get("node_kind"),"LESSON"),start,ZoneId.of(zone)).stream().limit(5).toList())
                overrides.addObject().put("method","popup").put("minutes",offset);
        body.putObject("extendedProperties").putObject("private").put("veloceSession",slot.get("id").toString());return body;
    }

    static ObjectNode reminderEvent(Json json,ReminderRules.CalendarReminder reminder,String zone) {
        var body=json.object().put("summary",reminder.title()).put("transparency","transparent");
        body.putObject("start").put("dateTime",reminder.at().toString()).put("timeZone",zone);
        body.putObject("end").put("dateTime",reminder.at().plusSeconds(60).toString()).put("timeZone",zone);
        body.putObject("reminders").put("useDefault",false).putArray("overrides").addObject().put("method","popup").put("minutes",0);
        body.putObject("extendedProperties").putObject("private").put("veloceSession",reminder.id().toString());
        return body;
    }
    private static final class RemoteFailure extends Problem {
        final int status;RemoteFailure(int status){super(502,"GOOGLE_CALENDAR_FAILED",status==400||status==401||status==403?
            "Google Calendar needs attention. Reconnect and check that the Calendar API is enabled and permission is granted.":"Google Calendar could not complete the sync (HTTP "+status+"). Veloce will retry.");this.status=status;}
    }
    private JsonNode call(String method,String url,String access,String body,boolean form) {
        try{var builder=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
            if(access!=null)builder.header("Authorization","Bearer "+access);
            if(body!=null)builder.header("Content-Type",form?"application/x-www-form-urlencoded":"application/json");
            var request=builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());String raw;
            try(var stream=response.body()){var bytes=stream.readNBytes(150_001);if(bytes.length>150_000)throw new IOException();raw=new String(bytes,StandardCharsets.UTF_8);}
            if(response.statusCode()<200||response.statusCode()>=300)throw new RemoteFailure(response.statusCode());
            return raw.isBlank()?ws.json.object():ws.json.read(raw);
        }catch(Problem e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new Problem(502,"GOOGLE_CALENDAR_UNAVAILABLE","Google Calendar could not be reached. Veloce will retry.");}
    }
    private String nonce(){var bytes=new byte[48];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static String form(Map<String,String> values){return values.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).collect(java.util.stream.Collectors.joining("&"));}
}

package com.veloce.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.veloce.common.*;
import com.veloce.workspace.WorkspaceStore.Workspace;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspaces/{w}/study")
public class StudyPracticeApi {
    private final StudyStore s;
    private final StudyPreferences preferences;
    private final StudyCoach coach;
    private final TransactionTemplate tx;
    private final Limits limits;
    StudyPracticeApi(StudyStore s,StudyPreferences preferences,StudyCoach coach,TransactionTemplate tx,Limits limits) {
        this.s=s;this.preferences=preferences;this.coach=coach;this.tx=tx;this.limits=limits;
    }
    private <T> T commit(UUID owner,UUID w,Function<Workspace,T> action) {
        return tx.execute(t->{s.ws.accountRevision(owner,true);var workspace=s.ws.owned(owner,w,true);s.writable(workspace);return action.apply(workspace);});
    }
    private StudyStore.Record required(Workspace workspace,UUID id) {
        return s.get(workspace.id(),s.roadmap(workspace),"PRACTICE",id.toString()).orElseThrow(Problem::missing);
    }
    public static long elapsed(JsonNode data,Instant now) {
        long used=data.path("usedSeconds").asLong();
        if (data.path("status").asText().equals("ACTIVE")&&data.hasNonNull("activeSince")) {
            Instant start=Instant.parse(data.path("activeSince").asText());
            Instant stop=now;
            if (data.hasNonNull("lastSeenAt")) {
                Instant presence=Instant.parse(data.path("lastSeenAt").asText()).plusSeconds(90);
                if (presence.isBefore(stop)) stop=presence;
            }
            used+=Math.max(0,Duration.between(start,stop).getSeconds());
        }
        return Math.min(21_600,used);
    }
    public static int answers(JsonNode data) {
        int count=0; for (var q:data.path("questions")) if (q.has("answer")) count++; return count;
    }
    public static boolean canFinish(JsonNode data,Instant now) {
        return answers(data)>=3&&elapsed(data,now)>=Math.min(15,data.path("targetMinutes").asInt(20))*60L;
    }
    private ObjectNode view(StudyStore.Record row) {
        var data=(ObjectNode)row.data().deepCopy();
        data.put("id",row.key()).put("version",row.version()).put("elapsedSeconds",elapsed(data,s.clock.instant()))
            .put("serverTime",s.clock.instant().toString()).put("answeredQuestions",answers(data)).put("canFinish",canFinish(data,s.clock.instant()));
        return data;
    }
    private void anotherActive(UUID owner,String id) {
        record Idle(UUID workspace,UUID roadmap,StudyStore.Record row) {}
        var idle=s.ws.db.query("""
            SELECT r.* FROM study_records r JOIN workspaces w ON w.id=r.workspace_id
            WHERE w.owner_id=? AND r.record_type='PRACTICE' AND r.data->>'status'='ACTIVE'
              AND (r.data->>'lastSeenAt')::timestamptz < now()-interval '90 seconds'
            FOR UPDATE OF r
            """,(r,n)->new Idle(r.getObject("workspace_id",UUID.class),r.getObject("roadmap_id",UUID.class),s.map(r,n)),owner);
        for (var item:idle) {
            var row=item.row();var data=(ObjectNode)row.data().deepCopy();
            data.put("usedSeconds",elapsed(data,s.clock.instant())).put("status","PAUSED");data.remove("activeSince");
            s.save(item.workspace(),item.roadmap(),"PRACTICE",row.key(),row.nodeId(),row.date(),data,row.version(),UUID.randomUUID());
        }
        boolean active=Boolean.TRUE.equals(s.ws.db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM study_records r JOIN workspaces w ON w.id=r.workspace_id
              WHERE w.owner_id=? AND r.record_type='PRACTICE' AND r.record_key<>? AND r.data->>'status'='ACTIVE')
            """,Boolean.class,owner,id));
        if (active) throw Problem.conflict("Pause the active practice session before starting another.");
    }
    private ObjectNode context(Workspace workspace,JsonNode data,String answer) {
        var context=s.json.object().put("industry",workspace.industry()).put("role",workspace.targetRole());
        context.set("topic",s.json.mapper.valueToTree(s.node(workspace.id(),s.roadmap(workspace),data.path("nodeId").asText())));
        context.set("report",data.path("report"));
        var history=context.putArray("previousQuestions");
        int size=data.path("questions").size();
        for (int i=Math.max(0,size-6);i<size;i++) {
            var original=data.path("questions").get(i);var item=history.addObject();
            item.put("question",clip(original.path("text").asText(),1200)).put("kind",original.path("kind").asText());
            if (original.has("answer")) {
                item.put("answer",clip(original.path("answer").path("text").asText(),1600));
                item.put("feedback",clip(original.path("answer").path("evaluation").path("feedback").asText(),600));
            }
        }
        if (answer!=null) context.put("latestAnswer",answer);
        String raw=s.json.write(context);
        if (raw.length()>32_000) {
            context.remove("report");
            context.put("reportSummary",data.path("report").path("summary").asText().substring(0,Math.min(4000,data.path("report").path("summary").asText().length())));
            if (s.json.write(context).length()>50_000) throw Problem.invalid("Shorten long earlier practice answers before requesting another AI turn.");
        }
        return context;
    }
    private static String clip(String text,int max) { return text.substring(0,Math.min(max,text.length())); }
    private ObjectNode question(String text,String kind) {
        return s.json.object().put("id",UUID.randomUUID().toString()).put("text",text).put("kind",kind).put("askedAt",s.clock.instant().toString());
    }
    private String manual(Map<String,Object> topic,int index) {
        return switch(index%4) {
            case 0 -> "Explain "+topic.get("title")+". Give a concrete example and describe how you would check your understanding.";
            case 1 -> "Apply "+topic.get("title")+" to a practical problem. Show your approach, solution and validation.";
            case 2 -> "What can go wrong when applying "+topic.get("title")+"? Explain trade-offs and difficult cases.";
            default -> "Review your work on "+topic.get("title")+". What would you improve and why?";
        };
    }
    @GetMapping("/practice")
    Object sessions(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@RequestParam(defaultValue="0") int page) {
        if (page<0||page>10000) throw Problem.invalid("Choose a practice history page.");
        var workspace=s.ws.owned(owner,w);
        return s.ws.db.query("""
            SELECT workspace_id,roadmap_id,record_type,record_key,node_id,study_date,version,
              data-'questions'-'report' AS data,updated_at,
              jsonb_array_length(jsonb_path_query_array(data,'$.questions[*].answer')) AS answered
            FROM study_records WHERE workspace_id=? AND roadmap_id=? AND record_type='PRACTICE'
            ORDER BY updated_at DESC,record_key LIMIT 100 OFFSET ?
            """,(r,n)->{
                var result=view(s.map(r,n));result.put("answeredQuestions",r.getInt("answered"));result.put("summaryOnly",true);return result;
            },w,s.roadmap(workspace),page*100);
    }
    @PostMapping("/practice")
    Object start(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@RequestBody JsonNode input) {
        limits.check("practice:"+owner,30,60);
        var workspace=s.ws.owned(owner,w); s.writable(workspace); var roadmap=s.roadmap(workspace);
        if (!workspace.status().equals("ACTIVE")) throw Problem.conflict("Resume this workspace before starting timed practice.");
        UUID id=StudyRules.mutation(input);
        var existing=s.get(w,roadmap,"PRACTICE",id.toString());
        if (existing.isPresent()) {
            if (!existing.get().nodeId().equals(input.path("nodeId").asText())||!existing.get().data().path("mode").asText().equals(input.path("mode").asText()))
                throw Problem.conflict("That practice identifier belongs to a different session.");
            return view(existing.get());
        }
        String node=Json.text(input,"nodeId",80,true); var topic=s.node(w,roadmap,node);
        String mode=Json.text(input,"mode",10,true);
        if (!Set.of("MANUAL","AI").contains(mode)) throw Problem.invalid("Choose manual or AI practice.");
        var pref=preferences.get(w);
        var data=s.json.object().put("nodeId",node).put("title",topic.get("title").toString()).put("mode",mode)
            .put("status","ACTIVE").put("usedSeconds",0).put("targetMinutes",pref.path("practiceMinutes").asInt(20))
            .put("provider",pref.path("provider").asText()).put("model",pref.path("model").asText())
            .put("activeSince",s.clock.instant().toString()).put("lastSeenAt",s.clock.instant().toString());
        String text=manual(topic,0);
        if (mode.equals("AI")) {
            LocalDate date=StudyRules.date(Json.text(input,"date",10,true));
            var reports=s.ws.db.query("""
                SELECT data,work_fingerprint FROM study_reports WHERE workspace_id=? AND roadmap_id=? AND study_date=?
                ORDER BY version DESC LIMIT 1
                """,(r,n)->Map.of("data",s.json.read(r.getString(1)),"fingerprint",r.getString(2)),w,roadmap,date);
            var entries=s.day(w,roadmap,date);
            var planned=s.ws.db.queryForList("""
                SELECT DISTINCT node_id FROM schedule_slots x WHERE workspace_id=? AND study_date=? AND kind='STUDY' AND NOT cancelled
                  AND (plan_id=? OR EXISTS(SELECT 1 FROM session_completions c WHERE c.session_id=x.id))
                """,String.class,w,date,workspace.activePlanId());
            if (reports.isEmpty()||!StudyRules.reportIssues(entries,planned).isEmpty()||!reports.getFirst().get("fingerprint").equals(s.fingerprint(entries)))
                throw Problem.conflict("Submit a report covering the latest saved work for this date before starting report-based AI practice.");
            data.set("report",(JsonNode)reports.getFirst().get("data"));
            var turn=coach.turn(w,data.path("provider").asText(),data.path("model").asText(),context(workspace,data,null),"ORAL");
            text=turn.path("question").asText();
        }
        data.putArray("questions").add(question(text,"ORAL"));
        return commit(owner,w,current->{
            anotherActive(owner,id.toString());
            data.put("activeSince",s.clock.instant().toString()).put("lastSeenAt",s.clock.instant().toString());
            return view(s.save(w,roadmap,"PRACTICE",id.toString(),node,s.today(current),data,0,id));
        });
    }
    @GetMapping("/practice/{id}")
    Object session(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable UUID id) {
        var current=s.ws.owned(owner,w);
        if (current.status().equals("ARCHIVED")) return view(required(current,id));
        return commit(owner,w,workspace->{
            var row=required(workspace,id); var data=(ObjectNode)row.data().deepCopy();
            if (data.path("status").asText().equals("ACTIVE")) {
                boolean idle=s.clock.instant().isAfter(Instant.parse(data.path("lastSeenAt").asText()).plusSeconds(90));
                if (idle) {
                    data.put("usedSeconds",elapsed(data,s.clock.instant())).put("status","PAUSED");data.remove("activeSince");
                    row=s.save(w,s.roadmap(workspace),"PRACTICE",id.toString(),row.nodeId(),row.date(),data,row.version(),UUID.randomUUID());
                } else {
                    data.put("lastSeenAt",s.clock.instant().toString());
                    s.ws.db.update("UPDATE study_records SET data=?::jsonb WHERE workspace_id=? AND roadmap_id=? AND record_type='PRACTICE' AND record_key=?",
                        s.json.write(data),w,s.roadmap(workspace),id.toString());
                    row=new StudyStore.Record(row.type(),row.key(),row.nodeId(),row.date(),row.version(),data,row.updatedAt());
                }
            }
            return view(row);
        });
    }
    @PostMapping("/practice/{id}/answer")
    Object answer(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable UUID id,@RequestBody JsonNode input) {
        limits.check("practice-turn:"+owner,30,60);
        var workspace=s.ws.owned(owner,w); s.writable(workspace);
        var original=required(workspace,id);
        UUID mutation=StudyRules.mutation(input);
        for (var q:original.data().path("questions")) if (q.path("answer").path("mutationId").asText().equals(mutation.toString())) {
            if (!q.path("id").asText().equals(input.path("questionId").asText())||!q.path("answer").path("text").asText().equals(input.path("answer").asText()))
                throw Problem.conflict("That answer identifier was already used for a different answer.");
            return view(original);
        }
        if (StudyRules.version(input)!=original.version()) throw Problem.conflict("The practice session changed. Refresh it; your answer is retained.");
        if (Set.of("COMPLETED","CARRIED").contains(original.data().path("status").asText())) throw Problem.conflict("Open or resume this practice session before answering.");
        String answer=Json.text(input,"answer",16_000,true), questionId=Json.text(input,"questionId",36,true);
        var data=(ObjectNode)original.data().deepCopy();
        var questions=(ArrayNode)data.path("questions"); var last=(ObjectNode)questions.get(questions.size()-1);
        if (!last.path("id").asText().equals(questionId)||last.has("answer")) throw Problem.conflict("This question was already answered or changed.");
        if (questions.size()>=40) throw Problem.invalid("Finish this session before starting another set of questions.");
        int rating=StudyRules.number(input,"selfRating",0,5,0);
        var record=s.json.object().put("text",answer).put("mutationId",mutation.toString()).put("answeredAt",s.clock.instant().toString())
            .put("source",input.path("source").asText().equals("SPEECH")?"CLIENT_SPEECH":"TYPED");
        String next=manual(s.node(w,s.roadmap(workspace),original.nodeId()),questions.size());
        String kind=questions.size()%3==1?"PRACTICAL":"ORAL";
        if (data.path("mode").asText().equals("AI")) {
            var turn=coach.turn(w,data.path("provider").asText(),data.path("model").asText(),context(workspace,data,answer),kind);
            var evaluation=record.putObject("evaluation");
            for (String field:List.of("correctness","reasoning","clarity","coverage","feedback","improvement")) evaluation.set(field,turn.path(field));
            next=turn.path("question").asText();
        } else {
            record.put("selfRating",rating).put("reflection",Json.text(input,"reflection",4000,false));
        }
        last.set("answer",record);
        questions.add(question(next,kind));
        return commit(owner,w,current->view(s.save(w,s.roadmap(current),"PRACTICE",id.toString(),original.nodeId(),original.date(),
            data,original.version(),mutation)));
    }
    @PostMapping("/practice/{id}/{action}")
    Object action(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable UUID id,@PathVariable String action,@RequestBody JsonNode input) {
        return commit(owner,w,workspace->{
            var row=required(workspace,id); var data=(ObjectNode)row.data().deepCopy();
            if (StudyRules.version(input)!=row.version()) throw Problem.conflict("The practice session changed. Refresh before continuing.");
            if (data.path("status").asText().equals("COMPLETED")) return view(row);
            long used=elapsed(data,s.clock.instant());
            data.put("usedSeconds",used); data.remove("activeSince");
            if (action.equals("resume")) {
                if (!workspace.status().equals("ACTIVE")) throw Problem.conflict("Resume this workspace before resuming timed practice.");
                if (data.hasNonNull("targetDate")&&StudyRules.date(data.path("targetDate").asText()).isAfter(s.today(workspace)))
                    throw Problem.conflict("This practice session is carried to "+data.path("targetDate").asText()+".");
                anotherActive(owner,id.toString());
                data.put("status","ACTIVE").put("activeSince",s.clock.instant().toString()).put("lastSeenAt",s.clock.instant().toString());
            } else if (action.equals("pause")) data.put("status","PAUSED");
            else if (action.equals("finish")) {
                if (!canFinish(row.data(),s.clock.instant())) throw Problem.conflict("Complete three answers and at least "+Math.min(15,data.path("targetMinutes").asInt())+" active minutes before finishing. You can pause or carry the session.");
                data.put("status","COMPLETED").put("completedAt",s.clock.instant().toString());
            } else if (action.equals("carry")) {
                var target=StudyRules.date(Json.text(input,"targetDate",10,true));
                if (!target.isAfter(s.today(workspace))) throw Problem.invalid("Carry practice to a later date.");
                data.put("status","CARRIED").put("targetDate",target.toString());
                var key="practice-"+id;
                var old=s.get(w,s.roadmap(workspace),"CARRY",key);
                var carry=s.json.object().put("nodeId",row.nodeId()).put("title","Practice · "+data.path("title").asText())
                    .put("sourceDate",s.today(workspace).toString()).put("targetDate",target.toString()).put("status","OPEN")
                    .put("estimatedMinutes",Math.max(1,data.path("targetMinutes").asInt()-(int)(used/60)))
                    .put("reason","Continue the saved practice session").put("notes","").put("practiceId",id.toString());
                s.save(w,s.roadmap(workspace),"CARRY",key,row.nodeId(),target,carry,old.map(StudyStore.Record::version).orElse(0L),UUID.randomUUID());
            } else throw Problem.invalid("Choose a practice action.");
            var saved=s.save(w,s.roadmap(workspace),"PRACTICE",id.toString(),row.nodeId(),row.date(),data,row.version(),StudyRules.mutation(input));
            if (action.equals("finish")) {
                var carry=s.get(w,s.roadmap(workspace),"CARRY","practice-"+id);
                if (carry.isPresent()) {
                    var resolved=(ObjectNode)carry.get().data().deepCopy();resolved.put("status","DONE");
                    s.save(w,s.roadmap(workspace),"CARRY",carry.get().key(),row.nodeId(),carry.get().date(),resolved,carry.get().version(),UUID.randomUUID());
                }
            }
            return view(saved);
        });
    }
}

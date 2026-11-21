package com.veloce.study;

import com.veloce.common.Problem;
import com.veloce.workspace.WorkspaceStore.Workspace;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Re-times the unchecked part of one study day while the account/workspace locks are held. */
@Component
public class StudyDaySchedule {
    private final StudyStore s;
    StudyDaySchedule(StudyStore s) { this.s=s; }

    record Block(UUID id,Instant start,Instant end,int minutes,String kind,boolean protectedWork) {}
    record Occupied(Instant start,Instant end) {}
    record Placement(UUID id,Instant start,Instant end) {}

    void start(Workspace workspace,LocalDate date,Instant checkIn) {
        var blocks=s.ws.db.query("""
            SELECT x.id,x.starts_at,x.ends_at,x.minutes,x.kind,
              (EXISTS(SELECT 1 FROM session_completions c WHERE c.session_id=x.id)
               OR EXISTS(SELECT 1 FROM focus_sessions f WHERE f.session_id=x.id)) AS protected_work
            FROM schedule_slots x WHERE x.workspace_id=? AND x.plan_id=? AND x.study_date=? AND NOT x.cancelled
            ORDER BY x.starts_at,x.id
            """,(r,n)->new Block(r.getObject(1,UUID.class),r.getObject(2,OffsetDateTime.class).toInstant(),
                r.getObject(3,OffsetDateTime.class).toInstant(),r.getInt(4),r.getString(5),r.getBoolean(6)),
            workspace.id(),workspace.activePlanId(),date);
        var pending=pending(blocks);
        Set<UUID> moving=new HashSet<>(); pending.forEach(b->moving.add(b.id()));
        var unusedBreaks=blocks.stream().filter(b->b.kind().equals("BREAK")&&!moving.contains(b.id())&&b.end().isAfter(checkIn)).toList();
        if (pending.isEmpty()&&unusedBreaks.isEmpty()) return;
        Instant midnight=date.plusDays(1).atStartOfDay(s.zone(workspace)).toInstant();
        var occupied=s.ws.db.query("""
            SELECT x.id,x.starts_at,x.ends_at FROM schedule_slots x JOIN workspaces w ON w.id=x.workspace_id
            WHERE w.owner_id=? AND x.ends_at>? AND x.starts_at<? AND NOT x.cancelled
              AND NOT(x.workspace_id=? AND x.study_date=? AND x.kind='BREAK')
              AND NOT EXISTS(SELECT 1 FROM session_completions c WHERE c.session_id=x.id)
              AND ((w.status='ACTIVE' AND x.plan_id=w.active_plan_id)
                OR EXISTS(SELECT 1 FROM focus_sessions f WHERE f.session_id=x.id AND f.ended_at IS NULL))
            ORDER BY x.starts_at,x.ends_at
            """,(r,n)->Map.entry(r.getObject(1,UUID.class),new Occupied(r.getObject(2,OffsetDateTime.class).toInstant(),
                r.getObject(3,OffsetDateTime.class).toInstant())),workspace.owner(),utc(checkIn),utc(midnight),workspace.id(),date)
            .stream().filter(x->!moving.contains(x.getKey())).map(Map.Entry::getValue).toList();
        var placements=place(pending,occupied,checkIn,midnight);
        s.ws.db.batchUpdate("UPDATE schedule_slots SET starts_at=?,ends_at=? WHERE id=? AND workspace_id=?",
            placements,100,(ps,p)->{ ps.setObject(1,utc(p.start())); ps.setObject(2,utc(p.end()));
                ps.setObject(3,p.id()); ps.setObject(4,workspace.id()); });
        s.ws.db.batchUpdate("UPDATE schedule_slots SET cancelled=true WHERE id=? AND workspace_id=?",
            unusedBreaks,100,(ps,b)->{ps.setObject(1,b.id());ps.setObject(2,workspace.id());});
        s.ws.changed(workspace.id());
        s.ws.db.update("UPDATE accounts SET schedule_revision=schedule_revision+1 WHERE id=?",workspace.owner());
        s.ws.integrationChanged(workspace.owner(),workspace.id(),"study-check-in");
    }

    static List<Block> pending(List<Block> blocks) {
        var result=new ArrayList<Block>();
        Block gap=null;
        for (var block:blocks) {
            if (block.protectedWork()) { gap=null; continue; }
            if (block.kind().equals("BREAK")) { if (!result.isEmpty()) gap=block; continue; }
            if (gap!=null) result.add(gap);
            result.add(block); gap=null;
        }
        return result;
    }

    static List<Placement> place(List<Block> blocks,List<Occupied> occupied,Instant checkIn,Instant midnight) {
        var result=new ArrayList<Placement>();
        var busy=occupied.stream().sorted(Comparator.comparing(Occupied::start)).toList();
        Instant cursor=checkIn;
        for (var block:blocks) {
            long seconds=block.minutes()*60L;
            for (var interval:busy) {
                if (!interval.end().isAfter(cursor)) continue;
                if (!interval.start().isBefore(cursor.plusSeconds(seconds))) break;
                cursor=interval.end();
            }
            Instant end=cursor.plusSeconds(seconds);
            if (end.isAfter(midnight)) throw Problem.conflict(
                "Today's remaining study blocks and breaks do not fit before midnight. Adjust the timetable in workspace settings before checking in; no work has been removed.");
            result.add(new Placement(block.id(),cursor,end)); cursor=end;
        }
        return result;
    }

    private static OffsetDateTime utc(Instant value) { return value.atOffset(ZoneOffset.UTC); }
}

package com.veloce.study;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloce.workspace.WorkspaceStore.Workspace;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class StudyLifecycle {
    private final StudyStore s;
    public StudyLifecycle(StudyStore s) { this.s=s; }
    public void pause(Workspace workspace,String reason) {
        UUID w=workspace.id();
        s.ws.db.update("""
            UPDATE study_presence SET ended_at=now(),end_reason=?,break_seconds=break_seconds+
              CASE WHEN break_started_at IS NULL THEN 0 ELSE greatest(0,extract(epoch FROM(now()-break_started_at)))::bigint END,
              break_started_at=NULL WHERE workspace_id=? AND ended_at IS NULL
            """,reason,w);
        pauseTimers(workspace);
    }
    public void pauseTimers(Workspace workspace) {
        UUID w=workspace.id();
        s.ws.db.update("UPDATE focus_sessions SET ended_at=now() WHERE workspace_id=? AND ended_at IS NULL",w);
        if (workspace.activePlanId()==null) return;
        var roadmap=s.roadmap(workspace);
        var rows=s.ws.db.query("SELECT * FROM study_records WHERE workspace_id=? AND roadmap_id=? AND record_type='PRACTICE' AND data->>'status'='ACTIVE'",
            s::map,w,roadmap);
        for (var row:rows) {
            var data=(ObjectNode)row.data().deepCopy();
            data.put("usedSeconds",StudyPracticeApi.elapsed(data,s.clock.instant())).put("status","PAUSED");data.remove("activeSince");
            s.save(w,roadmap,"PRACTICE",row.key(),row.nodeId(),row.date(),data,row.version(),UUID.randomUUID());
        }
    }
}

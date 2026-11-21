package com.veloce.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloce.common.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceStore {
    public record Workspace(UUID id, UUID owner, String name, String industry, String targetRole, String status,
                            long revision, UUID activePlanId, int step, JsonNode data, UUID roadmapId, UUID sourceId) {}
    private static final String SELECT = """
        SELECT w.id,w.owner_id,w.name,w.industry,w.target_role,w.status,w.revision,w.active_plan_id,
               d.step,d.data,d.roadmap_id,d.current_source_id
        FROM workspaces w JOIN onboarding_drafts d ON d.workspace_id=w.id
        """;
    public final JdbcTemplate db;
    public final Json json;
    public WorkspaceStore(JdbcTemplate db, Json json) { this.db=db; this.json=json; }

    public Workspace owned(UUID owner, UUID id) { return owned(owner,id,false); }
    public Workspace owned(UUID owner, UUID id, boolean lock) {
        return db.query(SELECT+" WHERE w.owner_id=? AND w.id=?"+(lock?" FOR UPDATE OF w":""), this::map,owner,id)
            .stream().findFirst().orElseThrow(Problem::missing);
    }
    public List<Workspace> all(UUID owner,int page,int size) {
        return db.query(SELECT+" WHERE w.owner_id=? ORDER BY w.updated_at DESC,w.id LIMIT ? OFFSET ?",this::map,owner,size,(long)page*size);
    }
    public long accountRevision(UUID owner, boolean lock) {
        return db.query("SELECT schedule_revision FROM accounts WHERE id=?"+(lock?" FOR UPDATE":""),
            (r,n)->r.getLong(1), owner).stream().findFirst().orElseThrow(Problem::unauthorized);
    }
    public void changed(UUID id) {
        db.update("UPDATE workspaces SET revision=revision+1,updated_at=now() WHERE id=?",id);
    }
    public void expect(Workspace workspace,long revision) {
        if (workspace.revision()!=revision) throw Problem.conflict("This workspace changed in another view. Reload its latest version.");
    }
    public void draft(Workspace workspace) {
        if (!workspace.status().equals("DRAFT")) throw Problem.conflict("This workspace already has a plan. Use its settings to adjust the timetable.");
    }

    public void integrationChanged(UUID owner,UUID workspace,String kind) {
        db.update("""
            INSERT INTO integration_preferences(workspace_id,owner_id,revision) VALUES(?,?,1)
            ON CONFLICT(workspace_id) DO UPDATE SET revision=integration_preferences.revision+1,
               calendar_status=CASE WHEN integration_preferences.calendar_installation IS NULL THEN NULL ELSE 'Pending' END,
               notification_status=CASE WHEN integration_preferences.reminder_installation IS NULL THEN NULL ELSE 'Pending' END
            """,workspace,owner);
        db.update("""
            INSERT INTO outbox_events(id,workspace_id,revision,kind)
            SELECT ?,workspace_id,revision,? FROM integration_preferences WHERE workspace_id=? ON CONFLICT DO NOTHING
            """,UUID.randomUUID(),kind,workspace);
    }

    private Workspace map(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new Workspace(r.getObject("id",UUID.class),r.getObject("owner_id",UUID.class),r.getString("name"),
            r.getString("industry"),r.getString("target_role"),r.getString("status"),r.getLong("revision"),
            r.getObject("active_plan_id",UUID.class),r.getInt("step"),json.read(r.getString("data")),
            r.getObject("roadmap_id",UUID.class),r.getObject("current_source_id",UUID.class));
    }
}

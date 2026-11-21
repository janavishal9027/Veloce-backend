package com.veloce.roadmap;

import com.veloce.common.*;
import com.veloce.roadmap.Roadmap.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class RoadmapStore {
    private final JdbcTemplate db;
    private final Json json;
    RoadmapStore(JdbcTemplate db,Json json) { this.db=db; this.json=json; }

    UUID insert(UUID workspace,UUID source,Document doc) {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO roadmap_versions(id,workspace_id,source_id,title,industry,target_role) VALUES(?,?,?,?,?,?)",
            id,workspace,source,doc.title(),doc.industry(),doc.targetRole());
        var nodes=new ArrayList<Object[]>();
        var deps=new ArrayList<Object[]>();
        int i=0;
        for (Node n:doc.nodes()) {
            nodes.add(new Object[]{workspace,id,n.id(),i++,n.parentId(),n.kind().name(),n.priority().name(),
                n.title(),n.estimatedMinutes(),n.outcome(),json.write(n.resources())});
            for (String pre:n.dependsOn()) deps.add(new Object[]{workspace,id,n.id(),pre});
        }
        db.batchUpdate("""
            INSERT INTO roadmap_nodes(workspace_id,roadmap_id,node_id,ordinal,parent_id,kind,priority,title,estimated_minutes,outcome,resources)
            VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb)
            """,nodes);
        if (!deps.isEmpty()) db.batchUpdate("""
            INSERT INTO roadmap_dependencies(workspace_id,roadmap_id,node_id,prerequisite_id) VALUES(?,?,?,?)
            """,deps);
        return id;
    }

    public Document get(UUID workspace,UUID id) { return page(workspace,id,0,5000); }

    public Document page(UUID workspace,UUID id,int offset,int limit) {
        if (id==null) throw Problem.missing();
        var header=db.query("SELECT title,industry,target_role FROM roadmap_versions WHERE workspace_id=? AND id=?",
            (r,n)->List.of(r.getString(1),r.getString(2),r.getString(3)),workspace,id).stream().findFirst().orElseThrow(Problem::missing);
        var nodes=db.query("""
            SELECT n.*,coalesce((SELECT jsonb_agg(d.prerequisite_id ORDER BY d.prerequisite_id) FROM roadmap_dependencies d
              WHERE d.workspace_id=n.workspace_id AND d.roadmap_id=n.roadmap_id AND d.node_id=n.node_id),'[]'::jsonb) AS deps
            FROM roadmap_nodes n WHERE workspace_id=? AND roadmap_id=? ORDER BY ordinal LIMIT ? OFFSET ?
            """,(r,n)->new Node(r.getString("node_id"),r.getString("parent_id"),r.getString("title"),Kind.valueOf(r.getString("kind")),
                r.getInt("estimated_minutes"),List.of(json.as(json.read(r.getString("deps")),String[].class)),
                Priority.valueOf(r.getString("priority")),r.getString("outcome"),
                List.of(json.as(json.read(r.getString("resources")),Resource[].class))),workspace,id,limit,offset);
        return new Document(1,header.get(0),header.get(1),header.get(2),nodes);
    }
}

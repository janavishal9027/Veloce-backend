package com.veloce.roadmap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloce.common.Json;
import com.veloce.roadmap.ImportParser.Analysis;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reuse only a confirmed copy of the exact same file belonging to the same account. */
final class ImportMemory {
    private final JdbcTemplate db;
    private final Json json;
    private final ImportParser parser;
    ImportMemory(JdbcTemplate db,Json json,ImportParser parser) { this.db=db;this.json=json;this.parser=parser; }
    private record Previous(UUID workspace,UUID version,String analysis) {}

    Analysis recall(UUID source,Analysis fresh,JsonNode profile) {
        var previous=db.query("""
            SELECT v.workspace_id,v.id,prior.analysis FROM roadmap_sources current
            JOIN workspaces target ON target.id=current.workspace_id
            JOIN roadmap_sources prior ON prior.sha256=current.sha256 AND prior.format=current.format
              AND prior.id<>current.id AND prior.analysis IS NOT NULL AND prior.state='READY'
              AND (current.format<>'xlsx' OR coalesce(prior.profile->>'xlsxImportVersion','0')=coalesce(current.profile->>'xlsxImportVersion','0'))
            JOIN workspaces owned ON owned.id=prior.workspace_id AND owned.owner_id=target.owner_id
            JOIN roadmap_versions v ON v.workspace_id=prior.workspace_id AND v.source_id=prior.id
            WHERE current.id=? ORDER BY v.created_at DESC,v.id DESC LIMIT 1
            """,(r,n)->new Previous(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3)),source);
        if(previous.isEmpty()) return parser.prepare(fresh,profile);
        var saved=previous.getFirst();
        var candidate=(ObjectNode)json.mapper.valueToTree(new RoadmapStore(db,json).get(saved.workspace(),saved.version()));
        // Workspace identity remains independent; reuse topics, mapping and estimates only.
        candidate.set("industry",profile.path("industry"));candidate.set("targetRole",profile.path("targetRole"));
        var analysis=parser.review(json.as(json.read(saved.analysis()),Analysis.class),profile,candidate);
        var notes=new LinkedHashSet<>(analysis.preparationNotes());
        notes.add("Reused your previously confirmed roadmap, including topic corrections, worksheet choices and study estimates.");
        return new Analysis(analysis.candidate(),analysis.warnings(),analysis.issues(),analysis.headers(),analysis.rows(),analysis.mapping(),
            analysis.locations(),analysis.extractedText(),analysis.sheets(),analysis.assignedEstimates(),List.copyOf(notes),fresh.workbookSchedule());
    }
}

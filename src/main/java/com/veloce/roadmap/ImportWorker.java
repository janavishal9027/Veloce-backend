package com.veloce.roadmap;

import com.veloce.common.*;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ImportWorker {
    private final JdbcTemplate db;
    private final Json json;
    private final ImportParser parser;
    private final boolean enabled;
    ImportWorker(JdbcTemplate db,Json json,ImportParser parser,@Value("${veloce.jobs-enabled}") boolean enabled) {
        this.db=db; this.json=json; this.parser=parser; this.enabled=enabled;
    }
    record Claim(UUID id,String format,byte[] content,String profile) {}
    @Scheduled(fixedDelay=750)
    void tick() {
        if (!enabled) return;
        db.update("""
            UPDATE roadmap_sources SET state='FAILED',error='Import was interrupted. Upload the file again.',lease_until=NULL
            WHERE state='PARSING' AND lease_until<now() AND attempts>=3
            """);
        UUID token=UUID.randomUUID();
        var claims=db.query("""
            WITH next AS (
              SELECT id FROM roadmap_sources WHERE state='QUEUED' OR (state='PARSING' AND lease_until<now() AND attempts<3)
              ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE roadmap_sources s SET state='PARSING',attempts=attempts+1,claim_token=?,lease_until=now()+interval '2 minutes'
              FROM next WHERE s.id=next.id RETURNING s.id,s.format,s.content,s.profile
            """,(r,n)->new Claim(r.getObject(1,UUID.class),r.getString(2),r.getBytes(3),r.getString(4)),token);
        if (claims.isEmpty()) return;
        var claim=claims.getFirst();
        try {
            var profile=json.read(claim.profile);
            var analysis=new ImportMemory(db,json,parser).recall(claim.id,parser.analyze(claim.format,claim.content,profile),profile);
            db.update("""
                UPDATE roadmap_sources SET state='READY',analysis=?::jsonb,error=NULL,lease_until=NULL
                WHERE id=? AND claim_token=? AND state='PARSING'
                """,json.write(analysis),claim.id,token);
        } catch (Exception e) {
            if (!(e instanceof Problem)) LoggerFactory.getLogger(getClass()).warn("Import {} failed ({})",claim.id,e.getClass().getSimpleName());
            db.update("""
                UPDATE roadmap_sources SET state='FAILED',error=?,lease_until=NULL WHERE id=? AND claim_token=? AND state='PARSING'
                """,e instanceof Problem?e.getMessage():"The file could not be parsed. Try a smaller file or paste the text.",claim.id,token);
        }
    }
}

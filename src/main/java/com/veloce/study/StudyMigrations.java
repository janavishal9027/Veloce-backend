package com.veloce.study;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Small additive migration also runs when development schema initialization is disabled. */
@Component
@DependsOnDatabaseInitialization
class StudyMigrations implements InitializingBean {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    StudyMigrations(JdbcTemplate db,PlatformTransactionManager manager){this.db=db;this.transaction=new TransactionTemplate(manager);}
    @Override public void afterPropertiesSet() {
        transaction.executeWithoutResult(status->{
            db.execute("SELECT pg_advisory_xact_lock(1706202607)");
            if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM schema_versions WHERE version=7)",Boolean.class)))return;
            db.execute("ALTER TABLE study_records DROP CONSTRAINT IF EXISTS study_records_record_type_check");
            db.execute("ALTER TABLE study_records ADD CONSTRAINT study_records_record_type_check CHECK(record_type IN ('WORK','PROJECT','CARRY','PRACTICE','DRAFT','LEAVE'))");
            db.update("INSERT INTO schema_versions(version) VALUES(7) ON CONFLICT DO NOTHING");
        });
    }
}

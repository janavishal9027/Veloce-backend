BEGIN;
ALTER TABLE study_records DROP CONSTRAINT IF EXISTS study_records_record_type_check;
ALTER TABLE study_records ADD CONSTRAINT study_records_record_type_check
    CHECK(record_type IN ('WORK','PROJECT','CARRY','PRACTICE','DRAFT','LEAVE'));
INSERT INTO schema_versions(version) VALUES(7) ON CONFLICT DO NOTHING;
COMMIT;

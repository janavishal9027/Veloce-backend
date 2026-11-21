ALTER TABLE schedule_slots ADD COLUMN IF NOT EXISTS track_label varchar(120);
INSERT INTO schema_versions(version) VALUES(5) ON CONFLICT DO NOTHING;

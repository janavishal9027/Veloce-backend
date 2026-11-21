CREATE TABLE IF NOT EXISTS study_preferences (
    workspace_id uuid PRIMARY KEY REFERENCES workspaces(id) ON DELETE CASCADE,
    version bigint NOT NULL DEFAULT 0, data jsonb NOT NULL DEFAULT '{}',
    provider_keys jsonb NOT NULL DEFAULT '{}', updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS study_records (
    workspace_id uuid NOT NULL, roadmap_id uuid NOT NULL,
    record_type varchar(12) NOT NULL CHECK(record_type IN ('WORK','PROJECT','CARRY','PRACTICE','DRAFT')),
    record_key varchar(200) NOT NULL, node_id varchar(80), study_date date NOT NULL,
    version bigint NOT NULL CHECK(version>0), data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(workspace_id,roadmap_id,record_type,record_key),
    FOREIGN KEY(workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
    FOREIGN KEY(workspace_id,roadmap_id) REFERENCES roadmap_versions(workspace_id,id) ON DELETE CASCADE,
    FOREIGN KEY(workspace_id,roadmap_id,node_id) REFERENCES roadmap_nodes(workspace_id,roadmap_id,node_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS study_records_day ON study_records(workspace_id,roadmap_id,record_type,study_date);
CREATE TABLE IF NOT EXISTS study_record_history (
    workspace_id uuid NOT NULL, roadmap_id uuid NOT NULL, record_type varchar(12) NOT NULL,
    record_key varchar(200) NOT NULL, node_id varchar(80), study_date date NOT NULL,
    version bigint NOT NULL, mutation_id uuid NOT NULL, data jsonb NOT NULL, recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(workspace_id,roadmap_id,record_type,record_key,version), UNIQUE(workspace_id,mutation_id),
    FOREIGN KEY(workspace_id,roadmap_id,record_type,record_key)
      REFERENCES study_records(workspace_id,roadmap_id,record_type,record_key) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS study_reports (
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE, roadmap_id uuid NOT NULL,
    study_date date NOT NULL, version bigint NOT NULL CHECK(version>0), mutation_id uuid NOT NULL,
    work_fingerprint char(64) NOT NULL, data jsonb NOT NULL, submitted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(workspace_id,roadmap_id,study_date,version), UNIQUE(workspace_id,mutation_id),
    FOREIGN KEY(workspace_id,roadmap_id) REFERENCES roadmap_versions(workspace_id,id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS study_presence (
    id uuid PRIMARY KEY, owner_id uuid NOT NULL, workspace_id uuid NOT NULL, study_date date NOT NULL,
    started_at timestamptz NOT NULL DEFAULT now(), ended_at timestamptz, break_started_at timestamptz,
    break_seconds bigint NOT NULL DEFAULT 0 CHECK(break_seconds>=0), end_reason varchar(1000) NOT NULL DEFAULT '',
    CHECK(ended_at IS NULL OR ended_at>=started_at),
    FOREIGN KEY(owner_id,workspace_id) REFERENCES workspaces(owner_id,id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS one_open_study_presence ON study_presence(owner_id) WHERE ended_at IS NULL;
CREATE INDEX IF NOT EXISTS study_presence_day ON study_presence(workspace_id,study_date);
INSERT INTO schema_versions(version) VALUES (3) ON CONFLICT DO NOTHING;

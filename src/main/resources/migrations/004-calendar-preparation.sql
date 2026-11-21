CREATE TABLE IF NOT EXISTS google_calendar_authorizations (
    state_hash char(64) PRIMARY KEY, owner_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    verifier text NOT NULL, expires_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS google_authorization_owner ON google_calendar_authorizations(owner_id);
CREATE TABLE IF NOT EXISTS google_calendar_connections (
    owner_id uuid PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    email varchar(320) NOT NULL, calendar_id text NOT NULL,
    refresh_token text NOT NULL, access_token text NOT NULL, expires_at timestamptz NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'PENDING', last_error varchar(1000) NOT NULL DEFAULT '',
    last_sync timestamptz, next_attempt timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS google_calendar_events (
    owner_id uuid NOT NULL REFERENCES google_calendar_connections(owner_id) ON DELETE CASCADE,
    session_id uuid NOT NULL, event_id varchar(100) NOT NULL, fingerprint char(64) NOT NULL,
    ends_at timestamptz NOT NULL, PRIMARY KEY(owner_id,session_id), UNIQUE(owner_id,event_id)
);
CREATE TABLE IF NOT EXISTS study_day_enhancements (
    id uuid PRIMARY KEY, workspace_id uuid NOT NULL, roadmap_id uuid NOT NULL, study_date date NOT NULL,
    source_fingerprint char(64) NOT NULL, plan jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(workspace_id,roadmap_id,study_date,id),
    FOREIGN KEY(workspace_id,roadmap_id) REFERENCES roadmap_versions(workspace_id,id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS study_day_preparation (
    workspace_id uuid NOT NULL, roadmap_id uuid NOT NULL, study_date date NOT NULL,
    version bigint NOT NULL CHECK(version>0), enhancement_id uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(workspace_id,roadmap_id,study_date),
    FOREIGN KEY(workspace_id,roadmap_id,study_date,enhancement_id)
        REFERENCES study_day_enhancements(workspace_id,roadmap_id,study_date,id) ON DELETE CASCADE
);
INSERT INTO schema_versions(version) VALUES(4) ON CONFLICT DO NOTHING;

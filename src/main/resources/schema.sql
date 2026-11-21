CREATE TABLE IF NOT EXISTS schema_versions (
    version integer PRIMARY KEY, installed_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO schema_versions(version) VALUES (1) ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS accounts (
    id uuid PRIMARY KEY, email varchar(254) NOT NULL UNIQUE, display_name varchar(80) NOT NULL,
    password_hash varchar(100) NOT NULL, schedule_revision bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS refresh_tokens (
    token_hash char(64) PRIMARY KEY, account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS refresh_expires ON refresh_tokens(expires_at);
CREATE TABLE IF NOT EXISTS workspaces (
    id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    name varchar(120) NOT NULL, industry varchar(120) NOT NULL DEFAULT '', target_role varchar(120) NOT NULL DEFAULT '',
    status varchar(12) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE','PAUSED','ARCHIVED')),
    revision bigint NOT NULL DEFAULT 0, active_plan_id uuid, create_key varchar(100),
    updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(owner_id,id), UNIQUE(owner_id,create_key)
);
CREATE INDEX IF NOT EXISTS workspace_owner ON workspaces(owner_id, updated_at DESC);
CREATE TABLE IF NOT EXISTS onboarding_drafts (
    workspace_id uuid PRIMARY KEY REFERENCES workspaces(id) ON DELETE CASCADE,
    step integer NOT NULL DEFAULT 1 CHECK(step BETWEEN 1 AND 6), data jsonb NOT NULL DEFAULT '{}',
    roadmap_id uuid, current_source_id uuid
);
CREATE TABLE IF NOT EXISTS roadmap_sources (
    id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    filename varchar(180) NOT NULL, format varchar(10) NOT NULL, sha256 char(64) NOT NULL, content bytea NOT NULL,
    state varchar(12) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','PARSING','READY','FAILED')),
    lease_until timestamptz, claim_token uuid, attempts integer NOT NULL DEFAULT 0, profile jsonb NOT NULL,
    analysis jsonb, error text, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(workspace_id,id)
);
CREATE INDEX IF NOT EXISTS sources_queue ON roadmap_sources(state, lease_until, created_at);
CREATE INDEX IF NOT EXISTS sources_hash ON roadmap_sources(workspace_id, sha256);
CREATE TABLE IF NOT EXISTS roadmap_versions (
    id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    source_id uuid, title varchar(200) NOT NULL, industry varchar(120) NOT NULL, target_role varchar(120) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(workspace_id,id),
    FOREIGN KEY(workspace_id,source_id) REFERENCES roadmap_sources(workspace_id,id)
);
CREATE TABLE IF NOT EXISTS roadmap_nodes (
    workspace_id uuid NOT NULL, roadmap_id uuid NOT NULL, node_id varchar(80) NOT NULL, ordinal integer NOT NULL,
    parent_id varchar(80), kind varchar(12) NOT NULL, priority varchar(10) NOT NULL,
    title varchar(200) NOT NULL, estimated_minutes integer NOT NULL CHECK(estimated_minutes BETWEEN 0 AND 43200),
    outcome text NOT NULL, resources jsonb NOT NULL,
    PRIMARY KEY(workspace_id,roadmap_id,node_id), UNIQUE(workspace_id,roadmap_id,ordinal),
    FOREIGN KEY(workspace_id,roadmap_id) REFERENCES roadmap_versions(workspace_id,id) ON DELETE CASCADE,
    FOREIGN KEY(workspace_id,roadmap_id,parent_id) REFERENCES roadmap_nodes(workspace_id,roadmap_id,node_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE IF NOT EXISTS roadmap_dependencies (
    workspace_id uuid NOT NULL, roadmap_id uuid NOT NULL, node_id varchar(80) NOT NULL, prerequisite_id varchar(80) NOT NULL,
    PRIMARY KEY(workspace_id,roadmap_id,node_id,prerequisite_id),
    FOREIGN KEY(workspace_id,roadmap_id,node_id) REFERENCES roadmap_nodes(workspace_id,roadmap_id,node_id) ON DELETE CASCADE,
    FOREIGN KEY(workspace_id,roadmap_id,prerequisite_id) REFERENCES roadmap_nodes(workspace_id,roadmap_id,node_id)
);
CREATE TABLE IF NOT EXISTS plan_previews (
    id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE, roadmap_id uuid NOT NULL,
    workspace_revision bigint NOT NULL, schedule_revision bigint NOT NULL, planner_version integer NOT NULL,
    fingerprint char(64) NOT NULL, settings jsonb NOT NULL, schedule jsonb NOT NULL,
    feasible boolean NOT NULL, reschedule boolean NOT NULL, expires_at timestamptz NOT NULL,
    UNIQUE(workspace_id,id), FOREIGN KEY(workspace_id,roadmap_id) REFERENCES roadmap_versions(workspace_id,id)
);
CREATE TABLE IF NOT EXISTS plan_versions (
    id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE, roadmap_id uuid NOT NULL,
    preview_id uuid NOT NULL, status varchar(12) NOT NULL CHECK(status IN ('ACTIVE','SUPERSEDED')),
    settings jsonb NOT NULL, summary jsonb NOT NULL, activated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(workspace_id,id), UNIQUE(workspace_id,id,roadmap_id), UNIQUE(workspace_id,preview_id),
    FOREIGN KEY(workspace_id,roadmap_id) REFERENCES roadmap_versions(workspace_id,id),
    FOREIGN KEY(workspace_id,preview_id) REFERENCES plan_previews(workspace_id,id)
);
CREATE UNIQUE INDEX IF NOT EXISTS only_one_active_plan ON plan_versions(workspace_id) WHERE status='ACTIVE';
DO $$ BEGIN
    ALTER TABLE workspaces ADD CONSTRAINT active_plan_owned FOREIGN KEY (id,active_plan_id) REFERENCES plan_versions(workspace_id,id);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE onboarding_drafts ADD CONSTRAINT draft_roadmap_owned FOREIGN KEY (workspace_id,roadmap_id) REFERENCES roadmap_versions(workspace_id,id);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE onboarding_drafts ADD CONSTRAINT draft_source_owned FOREIGN KEY (workspace_id,current_source_id) REFERENCES roadmap_sources(workspace_id,id);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
CREATE TABLE IF NOT EXISTS schedule_slots (
    id uuid PRIMARY KEY, workspace_id uuid NOT NULL, plan_id uuid NOT NULL, roadmap_id uuid NOT NULL, node_id varchar(80),
    study_date date NOT NULL, starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL, minutes integer NOT NULL CHECK(minutes>0),
    kind varchar(12) NOT NULL CHECK(kind IN ('STUDY','BREAK')), cancelled boolean NOT NULL DEFAULT false,
    CHECK(starts_at<ends_at), CHECK((kind='BREAK')=(node_id IS NULL)), UNIQUE(workspace_id,id),
    FOREIGN KEY(workspace_id,plan_id,roadmap_id) REFERENCES plan_versions(workspace_id,id,roadmap_id),
    FOREIGN KEY(workspace_id,roadmap_id,node_id) REFERENCES roadmap_nodes(workspace_id,roadmap_id,node_id)
);
CREATE INDEX IF NOT EXISTS slots_workspace_date ON schedule_slots(workspace_id,study_date,starts_at);
CREATE INDEX IF NOT EXISTS slots_time ON schedule_slots(starts_at,ends_at) WHERE NOT cancelled;
CREATE TABLE IF NOT EXISTS session_completions (
    session_id uuid PRIMARY KEY, workspace_id uuid NOT NULL, completed_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(workspace_id,session_id) REFERENCES schedule_slots(workspace_id,id)
);
CREATE TABLE IF NOT EXISTS focus_sessions (
    id uuid PRIMARY KEY, owner_id uuid NOT NULL, workspace_id uuid NOT NULL, session_id uuid NOT NULL,
    started_at timestamptz NOT NULL DEFAULT now(), ended_at timestamptz, CHECK(ended_at IS NULL OR ended_at>=started_at),
    FOREIGN KEY(owner_id,workspace_id) REFERENCES workspaces(owner_id,id),
    FOREIGN KEY(workspace_id,session_id) REFERENCES schedule_slots(workspace_id,id)
);
CREATE UNIQUE INDEX IF NOT EXISTS one_running_focus ON focus_sessions(owner_id) WHERE ended_at IS NULL;
CREATE INDEX IF NOT EXISTS focus_workspace ON focus_sessions(workspace_id,session_id);
CREATE TABLE IF NOT EXISTS generation_jobs (
    id uuid PRIMARY KEY, workspace_id uuid NOT NULL, preview_id uuid NOT NULL, request_key varchar(100) NOT NULL,
    state varchar(12) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    stage varchar(28) NOT NULL DEFAULT 'Queued', lease_until timestamptz, claim_token uuid,
    attempts integer NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now(), plan_id uuid, error text,
    UNIQUE(workspace_id,request_key),
    FOREIGN KEY(workspace_id,preview_id) REFERENCES plan_previews(workspace_id,id),
    FOREIGN KEY(workspace_id,plan_id) REFERENCES plan_versions(workspace_id,id)
);
CREATE INDEX IF NOT EXISTS generation_queue ON generation_jobs(state,lease_until,created_at);
CREATE TABLE IF NOT EXISTS installations (
    id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(owner_id,id)
);
CREATE TABLE IF NOT EXISTS integration_preferences (
    workspace_id uuid PRIMARY KEY, owner_id uuid NOT NULL, calendar_installation uuid, calendar_id varchar(100),
    reminder_installation uuid, rules jsonb NOT NULL DEFAULT '{"channel":"OFF","rules":[]}', revision bigint NOT NULL DEFAULT 0,
    synced_through date, armed_through date, calendar_status varchar(40), notification_status varchar(40),
    CHECK((calendar_installation IS NULL)=(calendar_id IS NULL)),
    FOREIGN KEY(owner_id,workspace_id) REFERENCES workspaces(owner_id,id),
    FOREIGN KEY(owner_id,calendar_installation) REFERENCES installations(owner_id,id),
    FOREIGN KEY(owner_id,reminder_installation) REFERENCES installations(owner_id,id)
);
CREATE TABLE IF NOT EXISTS calendar_event_mappings (
    workspace_id uuid NOT NULL, owner_id uuid NOT NULL, installation_id uuid NOT NULL, session_id uuid NOT NULL,
    event_id varchar(100) NOT NULL, revision bigint NOT NULL, PRIMARY KEY(workspace_id,installation_id,session_id),
    FOREIGN KEY(owner_id,workspace_id) REFERENCES workspaces(owner_id,id),
    FOREIGN KEY(owner_id,installation_id) REFERENCES installations(owner_id,id),
    FOREIGN KEY(workspace_id,session_id) REFERENCES schedule_slots(workspace_id,id)
);
CREATE TABLE IF NOT EXISTS outbox_events (
    id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    revision bigint NOT NULL, kind varchar(40) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(workspace_id,revision,kind)
);

CREATE TABLE IF NOT EXISTS workspace_device_cleanup (
    owner_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    installation_id uuid NOT NULL, workspace_id uuid NOT NULL,
    calendar_writer boolean NOT NULL DEFAULT false, reminder_writer boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(owner_id,installation_id,workspace_id),
    FOREIGN KEY(owner_id,installation_id) REFERENCES installations(owner_id,id) ON DELETE CASCADE
);
INSERT INTO schema_versions(version) VALUES (2) ON CONFLICT DO NOTHING;

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
ALTER TABLE schedule_slots ADD COLUMN IF NOT EXISTS track_label varchar(120);
INSERT INTO schema_versions(version) VALUES(5) ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS social_identities (
    provider varchar(12) NOT NULL CHECK(provider IN ('GOOGLE','FACEBOOK')),
    subject varchar(255) NOT NULL,
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    linked_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(provider,subject), UNIQUE(account_id,provider)
);
INSERT INTO schema_versions(version) VALUES(6) ON CONFLICT DO NOTHING;
-- Additive carryover leave records share the existing revision and ownership guarantees.
ALTER TABLE study_records DROP CONSTRAINT IF EXISTS study_records_record_type_check;
ALTER TABLE study_records ADD CONSTRAINT study_records_record_type_check
    CHECK(record_type IN ('WORK','PROJECT','CARRY','PRACTICE','DRAFT','LEAVE'));
INSERT INTO schema_versions(version) VALUES(7) ON CONFLICT DO NOTHING;

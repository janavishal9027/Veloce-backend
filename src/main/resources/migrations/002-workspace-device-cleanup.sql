CREATE TABLE IF NOT EXISTS workspace_device_cleanup (
    owner_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    installation_id uuid NOT NULL, workspace_id uuid NOT NULL,
    calendar_writer boolean NOT NULL DEFAULT false, reminder_writer boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(owner_id,installation_id,workspace_id),
    FOREIGN KEY(owner_id,installation_id) REFERENCES installations(owner_id,id) ON DELETE CASCADE
);
INSERT INTO schema_versions(version) VALUES (2) ON CONFLICT DO NOTHING;

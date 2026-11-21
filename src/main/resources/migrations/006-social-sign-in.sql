CREATE TABLE IF NOT EXISTS social_identities (
    provider varchar(12) NOT NULL CHECK(provider IN ('GOOGLE','FACEBOOK')),
    subject varchar(255) NOT NULL,
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    linked_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(provider,subject), UNIQUE(account_id,provider)
);
INSERT INTO schema_versions(version) VALUES(6) ON CONFLICT DO NOTHING;

-- 0006_provider_credentials.up.sql
--
-- Encrypted provider credentials for multi-provider authentication.
-- Sensitive values are encrypted at the application level before storage.

CREATE TABLE IF NOT EXISTS lava_api.provider_credentials (
    provider_id         TEXT PRIMARY KEY,
    auth_type           TEXT NOT NULL DEFAULT 'none',
    username            TEXT,
    encrypted_password  TEXT,
    encrypted_token     TEXT,
    encrypted_api_key   TEXT,
    encrypted_api_secret TEXT,
    cookie_value        TEXT,
    expires_at          TIMESTAMPTZ,
    is_active           BOOLEAN NOT NULL DEFAULT true,
    last_used_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS provider_credentials_active_idx
    ON lava_api.provider_credentials (is_active, updated_at DESC);

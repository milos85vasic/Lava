-- 0007_provider_configs.up.sql
--
-- Per-provider user configuration: timeouts, mirrors, capability toggles.

CREATE TABLE IF NOT EXISTS lava_api.provider_configs (
    provider_id          TEXT PRIMARY KEY,
    timeout_ms           INTEGER NOT NULL DEFAULT 10000,
    preferred_mirror_url TEXT,
    is_enabled           BOOLEAN NOT NULL DEFAULT true,
    search_enabled       BOOLEAN NOT NULL DEFAULT true,
    browse_enabled       BOOLEAN NOT NULL DEFAULT true,
    download_enabled     BOOLEAN NOT NULL DEFAULT true,
    sort_preference      TEXT,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

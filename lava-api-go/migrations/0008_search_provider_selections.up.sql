-- 0008_search_provider_selections.up.sql
--
-- Per-search-query provider selection state for restoring user preferences.

CREATE TABLE IF NOT EXISTS lava_api.search_provider_selections (
    id          BIGSERIAL PRIMARY KEY,
    query_hash  TEXT NOT NULL,
    provider_id TEXT NOT NULL,
    is_selected BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (query_hash, provider_id)
);

CREATE INDEX IF NOT EXISTS search_provider_selections_query_idx
    ON lava_api.search_provider_selections (query_hash);

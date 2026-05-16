-- 0005_response_cache_realign.up.sql
--
-- Realign the response_cache table to the schema submodules/cache/pkg/postgres
-- expects (cache_key, value, expires_at). Migration 0001 was rewritten in
-- aa11566 but golang-migrate considers 0001 already applied on existing
-- deployments — the change only takes effect on a fresh database. Add a
-- distinct version that ALTERs the table on existing deployments.
--
-- This is destructive: the existing cache rows are dropped. That's fine for
-- a response cache (the entries will be repopulated on demand) and the audit
-- trail in request_audit (migration 0002) is preserved separately.

DROP TABLE IF EXISTS lava_api.response_cache;

CREATE TABLE IF NOT EXISTS lava_api.response_cache (
    cache_key   TEXT PRIMARY KEY,
    value       BYTEA NOT NULL,
    expires_at  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS response_cache_expires_at_idx
    ON lava_api.response_cache (expires_at);

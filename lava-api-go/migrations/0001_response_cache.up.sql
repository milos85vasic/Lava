-- 0001_response_cache.up.sql
--
-- Schema for the lava-api-go response cache. The submodules/cache/pkg/postgres
-- library expects (cache_key, value, expires_at) at minimum — its INSERT/SELECT
-- queries are hardcoded to those column names. We keep the schema minimal
-- here; richer audit fields (status, content-type, fetched_at, hit_count)
-- belong in request_audit (migration 0002) which is queried separately.
--
-- Caught by the Phase 14 k6 load run: the previous 7-column schema diverged
-- from submodules/cache's INSERT shape, every cache.Set() failed silently
-- (the handler dropped the error per fire-and-forget policy), and not a
-- single byte was ever cached in 2.0.0. The fix is mechanical and doesn't
-- change wire shape — only storage layout.

CREATE SCHEMA IF NOT EXISTS lava_api;

CREATE TABLE IF NOT EXISTS lava_api.response_cache (
    cache_key   TEXT PRIMARY KEY,
    value       BYTEA NOT NULL,
    expires_at  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS response_cache_expires_at_idx
    ON lava_api.response_cache (expires_at);

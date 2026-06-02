-- 0001_init.sql (SQLite dialect)
--
-- SQLite-backed response cache, mirroring the Postgres lava_api.response_cache
-- schema (migrations/0001_response_cache.up.sql + 0005_response_cache_realign).
-- The Postgres table is (cache_key TEXT PRIMARY KEY, value BYTEA, expires_at
-- TIMESTAMPTZ); the SQLite mirror is the same shape with portable types:
--   cache_key   TEXT PRIMARY KEY
--   value       BLOB NOT NULL        (BYTEA equivalent)
--   expires_at  INTEGER              (unix NANOSECONDS; NULL = never expires)
--
-- expires_at is stored as unix nanoseconds (not a SQL timestamp string) so the
-- backend can do lazy TTL expiry on read with sub-second precision, matching
-- the Postgres backend's `WHERE expires_at IS NULL OR expires_at > NOW()`
-- behavior. SQLite has no native DATETIME type; INTEGER nanos is the most
-- precise and most portable representation.

CREATE TABLE IF NOT EXISTS response_cache (
    cache_key   TEXT PRIMARY KEY,
    value       BLOB NOT NULL,
    expires_at  INTEGER
);

CREATE INDEX IF NOT EXISTS response_cache_expires_at_idx
    ON response_cache (expires_at);

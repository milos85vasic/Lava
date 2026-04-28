-- 0001_response_cache.up.sql
CREATE SCHEMA IF NOT EXISTS lava_api;

CREATE TABLE IF NOT EXISTS lava_api.response_cache (
    cache_key       TEXT PRIMARY KEY,
    upstream_status SMALLINT NOT NULL,
    body_brotli     BYTEA NOT NULL,
    content_type    TEXT NOT NULL,
    fetched_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    hit_count       BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS response_cache_expires_at_idx
    ON lava_api.response_cache (expires_at);

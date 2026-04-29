-- 0005_response_cache_realign.down.sql
DROP INDEX IF EXISTS lava_api.response_cache_expires_at_idx;
DROP TABLE IF EXISTS lava_api.response_cache;
-- Restore the (legacy) 7-column shape so a downgrade succeeds. Down migrations
-- are best-effort; the design-doc §7 schema is preserved here for reference.
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

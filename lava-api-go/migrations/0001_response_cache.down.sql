-- 0001_response_cache.down.sql
DROP INDEX IF EXISTS lava_api.response_cache_expires_at_idx;
DROP TABLE IF EXISTS lava_api.response_cache;

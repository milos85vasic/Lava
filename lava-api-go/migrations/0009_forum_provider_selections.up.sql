-- 0009_forum_provider_selections.up.sql
--
-- Per-forum-category provider selection state for restoring user preferences.

CREATE TABLE IF NOT EXISTS lava_api.forum_provider_selections (
    id          BIGSERIAL PRIMARY KEY,
    category_id TEXT NOT NULL,
    provider_id TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (category_id, provider_id)
);

CREATE INDEX IF NOT EXISTS forum_provider_selections_category_idx
    ON lava_api.forum_provider_selections (category_id);

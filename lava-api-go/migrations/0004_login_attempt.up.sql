CREATE TABLE IF NOT EXISTS lava_api.login_attempt (
    id              BIGSERIAL PRIMARY KEY,
    client_ip       INET NOT NULL,
    username_hash   TEXT NOT NULL,
    succeeded       BOOLEAN NOT NULL,
    attempted_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS login_attempt_lookup_idx
    ON lava_api.login_attempt (client_ip, attempted_at DESC);

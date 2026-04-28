CREATE TABLE IF NOT EXISTS lava_api.request_audit (
    id              BIGSERIAL PRIMARY KEY,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    method          TEXT NOT NULL,
    path            TEXT NOT NULL,
    query           TEXT,
    client_ip       INET,
    auth_realm_hash TEXT,
    upstream_status SMALLINT,
    upstream_ms     INTEGER,
    cache_outcome   TEXT NOT NULL,
    bytes_out       INTEGER
);

CREATE INDEX IF NOT EXISTS request_audit_received_at_idx
    ON lava_api.request_audit (received_at);

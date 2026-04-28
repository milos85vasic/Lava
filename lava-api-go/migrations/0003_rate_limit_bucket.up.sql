CREATE TABLE IF NOT EXISTS lava_api.rate_limit_bucket (
    client_ip       INET NOT NULL,
    route_class     TEXT NOT NULL,
    tokens          DOUBLE PRECISION NOT NULL,
    last_refill_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (client_ip, route_class)
);

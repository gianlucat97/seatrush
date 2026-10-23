-- The two tables of README section 11.1 that every service running a SAGA step owns.
--
-- This copy exists so the library can be tested against the real thing. The schema itself is
-- migrated by each service, which is why there is no Flyway migration in this module: a library
-- that migrated a service's database would own a schema it does not own.

CREATE TABLE outbox (
    id            BIGSERIAL PRIMARY KEY,
    aggregate_key TEXT        NOT NULL,
    topic         TEXT        NOT NULL,
    message       JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);

CREATE INDEX outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

CREATE TABLE processed_message (
    message_id   UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

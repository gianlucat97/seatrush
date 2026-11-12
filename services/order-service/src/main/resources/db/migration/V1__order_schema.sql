-- The order schema of README section 11.2.
--
-- The whole schema lands in one migration, including the ticket table this milestone does not
-- write yet. They are not spare parts: the unique constraints are the schema's half of the
-- correctness argument, and they only make sense read together. UNIQUE (user_id, idempotency_key)
-- is what makes a retried checkout one order, customer_order.hold_id UNIQUE is what makes one
-- hold one order whatever the SAGA does, and ticket's UNIQUE (event_id, seat_id) is the last
-- guard against two tickets for one seat. Assembling them across milestones would mean reviewing
-- each one without the others it depends on.

CREATE TABLE customer_order (
    id              UUID PRIMARY KEY,
    user_id         UUID        NOT NULL,
    event_id        UUID        NOT NULL,
    -- One hold becomes at most one order, whatever a client retries and whichever pod serves it.
    hold_id         UUID        NOT NULL UNIQUE,
    status          TEXT        NOT NULL,
    failure_reason  TEXT,
    total_cents     BIGINT      NOT NULL CHECK (total_cents > 0),
    currency        CHAR(3)     NOT NULL,
    idempotency_key UUID        NOT NULL,
    -- Detects key reuse with a different body (README section 9.4): the same key asking for a
    -- different hold is a confused client, and answering with the first order would be a lie.
    request_hash    TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The idempotency of POST /api/orders, enforced by the database rather than by a read: two
    -- concurrent retries both reach the insert, and exactly one of them can win it.
    UNIQUE (user_id, idempotency_key),
    -- The lifecycle is a closed set: a status nobody can advance must not reach an order.
    CONSTRAINT customer_order_status_known
        CHECK (status IN ('PENDING', 'PAYMENT_AUTHORIZED', 'SEATS_COMMITTED',
                          'CONFIRMED', 'COMPENSATING', 'FAILED')),
    CONSTRAINT customer_order_failure_reason_known
        CHECK (failure_reason IS NULL
               OR failure_reason IN ('PAYMENT_DECLINED', 'SEATS_UNAVAILABLE', 'TIMEOUT')),
    -- A failure always says why, a compensation may already know, and nothing else may carry a
    -- reason: a CONFIRMED order with one would be a contradiction a report would have to guess at.
    CONSTRAINT customer_order_failure_reason_matches_status
        CHECK (CASE status
                 WHEN 'FAILED' THEN failure_reason IS NOT NULL
                 WHEN 'COMPENSATING' THEN TRUE
                 ELSE failure_reason IS NULL
               END)
);

-- The order list of one user, newest first, which is what GET /api/orders pages through.
CREATE INDEX customer_order_user ON customer_order (user_id, created_at DESC);

CREATE TABLE order_item (
    order_id    UUID   NOT NULL REFERENCES customer_order (id),
    seat_id     TEXT   NOT NULL,
    price_cents BIGINT NOT NULL CHECK (price_cents > 0),
    PRIMARY KEY (order_id, seat_id)
);

-- The SAGA as a row (README section 9.7): what it is waiting for, and when to give up waiting.
CREATE TABLE saga_instance (
    order_id        UUID PRIMARY KEY REFERENCES customer_order (id),
    step            TEXT   NOT NULL,
    status          TEXT   NOT NULL,
    -- NULL once CommitSeats has been sent: past the pivot the SAGA waits for its reply for as
    -- long as it takes, so that a timeout can never fire while the commit is succeeding.
    deadline_at     TIMESTAMPTZ,
    -- When to try the capture again, after the pivot.
    next_attempt_at TIMESTAMPTZ,
    retries         INT    NOT NULL DEFAULT 0 CHECK (retries >= 0),
    -- Optimistic locking: a reply and a timeout can reach the same SAGA at once, and exactly
    -- one of them may advance it.
    version         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT saga_instance_status_known
        CHECK (status IN ('RUNNING', 'COMPENSATING', 'COMPLETED', 'FAILED')),
    CONSTRAINT saga_instance_step_known
        CHECK (step IN ('AWAITING_AUTHORIZATION', 'AWAITING_SEATS', 'AWAITING_CAPTURE',
                        'AWAITING_COMPENSATION', 'DONE'))
);

-- The timeout scheduler reads only the SAGAs that can still time out.
CREATE INDEX saga_deadline ON saga_instance (deadline_at) WHERE status = 'RUNNING';

-- And the retry scheduler only the ones with an attempt due.
CREATE INDEX saga_retry ON saga_instance (next_attempt_at) WHERE next_attempt_at IS NOT NULL;

CREATE TABLE ticket (
    id             UUID PRIMARY KEY,
    order_id       UUID        NOT NULL REFERENCES customer_order (id),
    event_id       UUID        NOT NULL,
    seat_id        TEXT        NOT NULL,
    signed_payload TEXT        NOT NULL,
    issued_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The second guard against a duplicate ticket: whatever the SAGA replays, one seat of one
    -- event is admitted once.
    UNIQUE (event_id, seat_id)
);

CREATE INDEX ticket_order ON ticket (order_id);

-- The Postgres outbox of README section 9.5, same structure as inventory. seatrush-messaging
-- owns the code that writes and relays it; the table belongs to the service that owns the
-- database it lives in.
CREATE TABLE outbox (
    id            BIGSERIAL PRIMARY KEY,
    aggregate_key TEXT        NOT NULL,
    topic         TEXT        NOT NULL,
    message       JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);

-- The relay polls for unpublished rows only, so the index holds just those.
CREATE INDEX outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

CREATE TABLE processed_message (
    message_id   UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The payment schema of README section 11.3.
--
-- One table of its own and the two that seatrush-messaging writes. The whole correctness argument
-- of this service is the unique constraint below: a provider that charged twice because a command
-- was redelivered would be the one bug a ticketing system cannot apologise its way out of.

CREATE TABLE payment_operation (
    id           UUID PRIMARY KEY,
    order_id     UUID        NOT NULL,
    operation    TEXT        NOT NULL,
    result       TEXT        NOT NULL,
    -- The amount of the authorization this operation belongs to, and its currency. Both are
    -- absent together on the one operation that belongs to no authorization: a void of an order
    -- that was never authorized. An amount stored without its currency is the bug Money exists
    -- to prevent, and inventing a currency for a no-op would be exactly that bug.
    amount_cents BIGINT      CHECK (amount_cents > 0),
    currency     CHAR(3),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Idempotency by (orderId, operation) (README section 6.5). It is a stronger guarantee than
    -- processed_message: that table recognises the same message arriving twice, while this one
    -- also refuses a second command asking for the same operation under a new message id, which
    -- is what order-service produces when it retries a step.
    UNIQUE (order_id, operation),
    CONSTRAINT payment_operation_known
        CHECK (operation IN ('AUTHORIZE', 'CAPTURE', 'VOID')),
    -- SUCCEEDED, DECLINED, or NOOP for a void of something that was never authorized.
    CONSTRAINT payment_operation_result_known
        CHECK (result IN ('SUCCEEDED', 'DECLINED', 'NOOP')),
    -- Only an authorization can be declined: a capture of a live authorization is not refused by
    -- a provider, and the SAGA has no message for one that is (README section 10.3).
    CONSTRAINT payment_operation_only_authorize_declines
        CHECK (result <> 'DECLINED' OR operation = 'AUTHORIZE'),
    -- And only a void can be a no-op, because it is the only command that is sent blindly.
    CONSTRAINT payment_operation_only_void_is_a_noop
        CHECK (result <> 'NOOP' OR operation = 'VOID'),
    CONSTRAINT payment_operation_amount_has_a_currency
        CHECK ((amount_cents IS NULL) = (currency IS NULL)),
    -- Only a no-op has nothing to report: everything else moved, or tried to move, real money.
    CONSTRAINT payment_operation_only_a_noop_has_no_amount
        CHECK (amount_cents IS NOT NULL OR result = 'NOOP')
);

-- The audit trail of one order, which is what the invariant checker reads.
CREATE INDEX payment_operation_order ON payment_operation (order_id, created_at);

-- The Postgres outbox of README section 9.5, same structure as the other services.
-- seatrush-messaging owns the code that writes and relays it; the table belongs to the service
-- that owns the database it lives in.
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

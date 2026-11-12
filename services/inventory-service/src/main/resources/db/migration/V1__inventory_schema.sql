-- The inventory schema of README section 11.1.
--
-- The whole schema lands in one migration, including the three tables this milestone does not
-- read yet (seat_sale, outbox, processed_message). They are not spare parts: seat_sale is the
-- primary key that makes overselling impossible (invariant I1) and the reason the commit
-- procedure is safe at all, so the schema that guarantees it is reviewed as one piece rather
-- than assembled across milestones.

CREATE TABLE event (
    id         UUID PRIMARY KEY,
    name       TEXT        NOT NULL,
    on_sale_at TIMESTAMPTZ NOT NULL,
    status     TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The lifecycle is a closed set: a status nobody can handle must not reach a seat map.
    CONSTRAINT event_status_known
        CHECK (status IN ('DRAFT', 'SCHEDULED', 'ON_SALE', 'SOLD_OUT', 'CLOSED'))
);

-- The catalog is listed by sale date, and the warm-up looks for the events that are due.
CREATE INDEX event_on_sale ON event (on_sale_at, status);

CREATE TABLE section (
    event_id    UUID   NOT NULL REFERENCES event (id),
    id          TEXT   NOT NULL,
    name        TEXT   NOT NULL,
    price_cents BIGINT NOT NULL CHECK (price_cents > 0),
    currency    CHAR(3) NOT NULL,
    PRIMARY KEY (event_id, id)
);

CREATE TABLE seat (
    event_id   UUID NOT NULL,
    -- section-row-number, for example A-12-7.
    id         TEXT NOT NULL,
    section_id TEXT NOT NULL,
    row_label  TEXT NOT NULL,
    number     INT  NOT NULL,
    -- Layout coordinates for the seat map, not a physical measurement.
    x          INT  NOT NULL,
    y          INT  NOT NULL,
    PRIMARY KEY (event_id, id),
    FOREIGN KEY (event_id, section_id) REFERENCES section (event_id, id)
);

-- The final guard for invariant I1: one row per sold seat, so a second sale of the same seat
-- cannot be committed whatever Redis believed.
CREATE TABLE seat_sale (
    event_id UUID        NOT NULL,
    seat_id  TEXT        NOT NULL,
    order_id UUID        NOT NULL,
    sold_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, seat_id),
    FOREIGN KEY (event_id, seat_id) REFERENCES seat (event_id, id)
);

-- The reconciler and the order lookups both start from the order.
CREATE INDEX seat_sale_order ON seat_sale (order_id);

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

-- What the webhook simulator (the receiving side) has seen. One row per payment: a redelivery increments
-- `deliveries` instead of creating a second row, which is the receiver-side deduplication.
CREATE TABLE receipts (
    payment_id uuid        PRIMARY KEY,
    client_id  text        NOT NULL,
    first_seen timestamptz NOT NULL DEFAULT now(),
    deliveries int         NOT NULL DEFAULT 1
);

-- The tuned dispatcher's work queue (F-05). One row per posted payment; the primary key deduplicates duplicate
-- payments.posted events. State: PENDING -> DELIVERED, or DEAD after the last failed attempt.
CREATE TABLE notifications (
    payment_id      uuid        PRIMARY KEY,
    client_id       text        NOT NULL,
    outcome         text        NOT NULL,
    state           text        NOT NULL,
    attempts        int         NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    posted_at       timestamptz NOT NULL,
    payload         text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    delivered_at    timestamptz
);
CREATE INDEX notifications_due_idx ON notifications (next_attempt_at) WHERE state = 'PENDING';

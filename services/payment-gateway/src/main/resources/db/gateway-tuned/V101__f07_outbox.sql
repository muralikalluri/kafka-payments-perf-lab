-- F-07 (tuned): payment rows and their outgoing events are committed together; a publisher sends
-- after commit, so no database transaction waits on the broker.
CREATE TABLE outbox (
    id         bigserial   PRIMARY KEY,
    msg_key    text        NOT NULL,
    payload    text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

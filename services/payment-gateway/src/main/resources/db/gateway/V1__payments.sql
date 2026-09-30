CREATE TABLE payments (
    payment_id          uuid PRIMARY KEY,
    client_id           text        NOT NULL,
    idempotency_key     text        NOT NULL,
    request_hash        bytea       NOT NULL,
    debtor_account_id   text        NOT NULL,
    creditor_account_id text        NOT NULL,
    merchant_id         text        NOT NULL,
    amount_minor        bigint      NOT NULL CHECK (amount_minor > 0),
    currency            char(3)     NOT NULL,
    debtor_seq          bigint      NOT NULL,
    status              text        NOT NULL,
    status_rank         smallint    NOT NULL,
    reason_code         text,
    response_body       text        NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);
-- F-06 (baseline, intentional): NO index on (client_id, idempotency_key). Duplicate detection
-- therefore scans the table; the advisory lock in PaymentService provides the uniqueness guarantee.

CREATE TABLE debtor_sequences (
    debtor_account_id text   PRIMARY KEY,
    last_seq          bigint NOT NULL
);

CREATE TABLE accounts (
    id                text    PRIMARY KEY,
    client_id         text    NOT NULL,
    currency          char(3) NOT NULL,
    overdraft         boolean NOT NULL DEFAULT false,
    balance_minor     bigint  NOT NULL,
    daily_limit_minor bigint  NOT NULL,
    last_seq          bigint  NOT NULL DEFAULT 0,
    -- Backstop for the invariant; the service checks first, under a row lock.
    CONSTRAINT balance_non_negative CHECK (overdraft OR balance_minor >= 0)
);

-- One row per payment, inserted in the same transaction as its postings. This is the
-- idempotent-consumer dedup record (ADR-0001).
CREATE TABLE ledger_payments (
    id                bigserial   PRIMARY KEY,
    payment_id        uuid        NOT NULL UNIQUE,
    debtor_account_id text        NOT NULL,
    debtor_seq        bigint      NOT NULL,
    outcome           text        NOT NULL,
    reason_code       text,
    applied_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE postings (
    id           bigserial   PRIMARY KEY,
    payment_id   uuid        NOT NULL REFERENCES ledger_payments(payment_id),
    account_id   text        NOT NULL,
    direction    char(1)     NOT NULL CHECK (direction IN ('D', 'C')),
    amount_minor bigint      NOT NULL CHECK (amount_minor > 0),
    created_at   timestamptz NOT NULL DEFAULT now()
);
-- F-06 (baseline, intentional): NO index on postings(account_id, created_at). The daily-outflow
-- limit check scans this table.

-- Payments that arrived ahead of their debtor sequence; drained when the gap fills (ADR-0002).
CREATE TABLE pending_payments (
    debtor_account_id text   NOT NULL,
    debtor_seq        bigint NOT NULL,
    payload           text   NOT NULL,
    PRIMARY KEY (debtor_account_id, debtor_seq)
);

-- Outcomes are published from here after commit, so a failed send never loses an outcome.
CREATE TABLE outbox (
    id         bigserial   PRIMARY KEY,
    msg_key    text        NOT NULL,
    payload    text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

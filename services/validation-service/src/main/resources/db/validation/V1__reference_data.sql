CREATE TABLE accounts (
    id        text    PRIMARY KEY,
    client_id text    NOT NULL,
    currency  char(3) NOT NULL,
    status    text    NOT NULL
);

CREATE TABLE account_limits (
    id               bigserial PRIMARY KEY,
    account_id       text   NOT NULL REFERENCES accounts(id),
    limit_type       text   NOT NULL,
    amount_usd_minor bigint NOT NULL
);

CREATE TABLE fx_rates (
    currency    char(3)        PRIMARY KEY,
    rate_to_usd numeric(18, 8) NOT NULL
);

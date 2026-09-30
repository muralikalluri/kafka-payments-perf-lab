-- F-11: every payment passes through a settlement account (debtor -> settlement -> creditor), so settlement nets to
-- zero at every commit. Baseline uses the single account SETTLE-USD for every posting (a hot row lock); tuned spreads
-- postings over the shard accounts SETTLE-USD-00 .. SETTLE-USD-15, chosen per transaction. Both sets exist in both
-- profiles so the schema is the same; only the choice differs. overdraft=false: settlement must never go negative, which
-- is why the credit leg is posted before the debit leg.
INSERT INTO accounts(id, client_id, currency, overdraft, balance_minor, daily_limit_minor)
SELECT 'SETTLE-USD', 'lab-settlement', 'USD', false, 0, 9000000000000000
UNION ALL
SELECT 'SETTLE-USD-' || lpad(n::text, 2, '0'), 'lab-settlement', 'USD', false, 0, 9000000000000000
FROM generate_series(0, 15) AS n;

-- Periodic reconciliation: the sum over all settlement accounts and how many are not zero (expected: none).
CREATE TABLE settlement_snapshots (
    id             bigserial   PRIMARY KEY,
    taken_at       timestamptz NOT NULL DEFAULT now(),
    accounts       int         NOT NULL,
    nonzero        int         NOT NULL,
    position_minor bigint      NOT NULL
);

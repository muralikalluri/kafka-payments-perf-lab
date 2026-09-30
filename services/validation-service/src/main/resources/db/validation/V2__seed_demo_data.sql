-- Fictional demo data for local runs and load tests.
INSERT INTO fx_rates(currency, rate_to_usd) VALUES
    ('USD', 1), ('EUR', 1.08), ('GBP', 1.27), ('INR', 0.012);

INSERT INTO accounts(id, client_id, currency, status)
SELECT 'ACC-' || lpad(n::text, 4, '0'), 'client-demo', 'USD',
       CASE WHEN n >= 999 THEN 'BLOCKED' ELSE 'ACTIVE' END
FROM generate_series(1, 1000) AS n;

INSERT INTO account_limits(account_id, limit_type, amount_usd_minor)
SELECT id, 'PER_TX', 1000000 FROM accounts;

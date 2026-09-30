-- Fictional demo accounts. 1,000,000.00 USD each, 50,000.00 USD daily outflow limit.
INSERT INTO accounts(id, client_id, currency, overdraft, balance_minor, daily_limit_minor)
SELECT 'ACC-' || lpad(n::text, 4, '0'), 'client-demo', 'USD', false, 100000000, 5000000
FROM generate_series(1, 1000) AS n;

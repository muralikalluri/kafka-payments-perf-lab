-- F-06 (tuned): the duplicate lookup by (client_id, idempotency_key) was a sequential scan.
CREATE UNIQUE INDEX payments_client_key_uq ON payments (client_id, idempotency_key);

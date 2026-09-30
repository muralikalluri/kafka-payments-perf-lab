-- F-06 (tuned): the daily-outflow limit check scanned every posting.
CREATE INDEX postings_account_created_idx ON postings (account_id, created_at);

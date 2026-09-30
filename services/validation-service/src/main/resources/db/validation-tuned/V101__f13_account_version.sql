-- F-13 (tuned): a per-account version that only increases. Cache entries and invalidation events carry it,
-- so a slow reader can never overwrite a newer cached value with an older one.
ALTER TABLE accounts ADD COLUMN version bigint NOT NULL DEFAULT 1;

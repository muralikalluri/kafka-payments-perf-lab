-- F-08 (tuned): account_limits was looked up by account_id with no index (once per account, per payment).
CREATE INDEX account_limits_account_idx ON account_limits (account_id);

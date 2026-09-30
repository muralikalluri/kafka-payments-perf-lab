# Architecture decision records

| ADR | Decision |
|---|---|
| [0001](0001-exactly-once-ledger-effect.md) | Exactly-once ledger effect through a dedup record, not Kafka transactions |
| [0002](0002-per-debtor-ordering-with-sequence-numbers.md) | Per-debtor ordering by sequence number, independent of the partition key |
| [0003](0003-metrics-semantics.md) | What the dashboard metrics mean, and what they must not be used for |
| [0004](0004-outbox-and-tuning.md) | Gateway outbox, and how tuned changes are gated (F-07) |
| [0005](0005-partition-sizing.md) | Partition count and record key (F-12) |
| [0006](0006-account-cache.md) | Distributed account cache (F-13) |
| [0007](0007-benchmark-method.md) | Benchmark method and result format |

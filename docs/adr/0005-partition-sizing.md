# ADR-0005: Partition count and record key (F-12)

Status: accepted (M4)

## Decision
Baseline uses 3 partitions keyed by merchant id. Tuned uses 12 partitions keyed by debtor account id
on every pipeline topic, including the dead-letter topics (a dead-letter record is published to the
same partition number as the original, so the DLT needs at least as many partitions).

## Rationale
- **Key.** A merchant id key sends all of a large merchant's payments to one partition, which caps
  that merchant at one consumer thread's throughput while other partitions idle. Debtor account id
  spreads load across many keys and keeps one debtor's records on one partition. Per-debtor ordering
  does not depend on this key; the ledger orders by sequence number (ADR-0002).
- **Count.** Sized as target throughput divided by the sustainable throughput of one partition
  consumer, rounded up with headroom for consumer scaling. The measured inputs (per-partition
  throughput and the target) come from benchmark runs: `result.json` records the offered rate per phase and
  the max sustainable rate; the per-partition consumer throughput is derived from those runs when the
  report is written. This document deliberately carries no figures so they cannot drift from the
  measurements.
- **Limits of the lab.** A single broker with replication factor 1 is a lab limitation. Partitions
  can be increased later but never decreased, so a profile switch needs the topics recreated
  (run-benchmark.sh resets the stack before every run).
- **Trade-off.** One very busy debtor becomes a hot partition in tuned. The load scenarios spread
  debtors widely; a single-debtor hot spot is not modelled.

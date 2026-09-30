# ADR-0002: Per-debtor ordering by sequence number, independent of the partition key

Status: accepted (M1)

## Context
Payments for one debtor account must be applied in acceptance order in both profiles. The tuned
profile keys records by debtor account id, which gives that order per partition. The baseline
profile deliberately keys by merchant id (finding F-12, hot partition), which spreads one debtor's
payments over several partitions and consumer threads, so partition order gives no guarantee.

## Decision
The gateway assigns a per-debtor sequence number (`debtor_seq`) inside the same transaction that
accepts the payment; the counter row lock makes sequence order equal commit order for that debtor.
The number travels on every event. The ledger applies a payment only when
`debtor_seq == accounts.last_seq + 1`. An early arrival is parked in `pending_payments` and drained,
in order, as soon as the gap fills. Validation rejections still flow through the pipeline so a
rejected payment consumes its sequence number and never blocks the next one.

Safety does not depend on ordering: both accounts are row-locked in id order before any check, so
balances cannot go negative and opposite transfers cannot deadlock.

## Consequences
- Baseline keeps the F-12 anti-pattern (merchant key, 3 partitions) and still honours the ordering
  rule; the cost of F-12 shows up as parked payments and lag, not as wrong balances.
- Gaps stall a debtor: a payment that never reaches the ledger (dead-lettered, or lost before the
  ledger) blocks later payments for that debtor. Recovery is manual in the MVP.
- Baseline sends to Kafka inside the gateway transaction (F-07). If the commit then fails, a
  phantom event may exist. The client's retry derives the same paymentId, but its sequence number
  is the debtor's next one, not necessarily the original. The ledger dedups on paymentId and, when a
  duplicate carries the next expected sequence, consumes that number without any effect, so later
  payments are not blocked. Two residual cases remain, both reachable only through this path:
  a different payment that reused the phantom's number is rejected with `SEQUENCE_CONFLICT`, and a
  different payment carrying an already-parked number is not stored. The tuned profile's gateway
  outbox removes the phantom event altogether.

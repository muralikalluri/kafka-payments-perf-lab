# ADR-0001: Exactly-once ledger effect via a dedup record, not Kafka transactions

Status: accepted (M1)

## Context
The ledger consumes `payments.validated` and writes postings to Postgres. Delivery from Kafka is
at-least-once, so the same payment can arrive more than once (consumer restart, gateway retry after
a failed commit, redelivery after an error).

## Decision
Idempotent consumer with a business-key dedup table. `ledger.ledger_payments.payment_id` is UNIQUE
and is written in the same local transaction as the postings and balance updates. A delivery whose
`payment_id` already exists is a no-op. `payment_id` is a deterministic UUIDv5 of
`clientId:idempotencyKey`, so replays from the client and redeliveries from Kafka collapse to one id.

The outcome event is written to `ledger.outbox` in the same transaction and published after commit.
The offset is committed only after the database transaction commits.

## Why not Kafka transactions
Kafka transactions give exactly-once only for Kafka-to-Kafka flows. The effect here is a Postgres
write, which sits outside the transaction, so a dedup record is needed regardless. The dedup record
also keeps working unchanged when the tuned profile moves to batch processing.

## Consequences
- Every accepted duplicate costs one extra lookup (the UNIQUE index on `payment_id`).
- Publishing goes through an outbox, so a failed send never loses an outcome; consumers of
  `payments.posted` must tolerate duplicates (the gateway's status update is monotonic).
- Insufficient funds is a business outcome (`REJECTED`), not an error, so it is never retried.
- Poison messages go to `payments.validated.DLT` after bounded retries. A payment that dead-letters
  leaves a gap in its debtor's sequence (see ADR-0002); that is a known limitation of the MVP.

## Addendum: a duplicate re-publishes the recorded outcome
A duplicate used to publish nothing, on the reasoning that the first delivery had already queued the outcome. That is not
enough: the first outcome can reach the gateway before the gateway has a row for the payment (a send from a gateway
transaction that rolled back, then the client's retry, whose own event is a duplicate at the ledger). The gateway's update
matches nothing, and the payment would stay ACCEPTED for good. So a payment seen again has its recorded outcome written to the
outbox again, with the same event identifier. This is safe because the gateway's status update is idempotent and monotonic,
and the ledger's effect is untouched. `LateRowTest` reproduces the case and fails without the re-publish.

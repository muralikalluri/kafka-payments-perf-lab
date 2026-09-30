# ADR-0004: Gateway outbox, and how tuned changes are gated (F-07)

Status: accepted (M4)

## Gating
Each tuned finding is a separate flag (`lab.tuning.fNN`, default false in the service's base config)
or plain keys in `<service>-tuned.yml`. The baseline config and baseline beans stay untouched, every
finding is its own commit, and `run-benchmark.sh` refuses `tuned` until the last M4 commit sets
`lab.tuning.complete: true`. Tuned-only Flyway migrations live in `db/<service>-tuned` with versions
from V100. Profiles are per database: a database migrated by tuned is not reused by baseline.

## F-07 in the gateway
Baseline accepts a payment inside one transaction that also waits for the broker
(`send().get()`), holding a connection, an advisory lock and the debtor's sequence row lock for the
whole round trip. Tuned:
- takes no advisory lock: the unique `(client_id, idempotency_key)` index from F-06 plus
  `INSERT ... ON CONFLICT DO NOTHING` decide the winner of a duplicate race;
- writes the payment and its event to an `outbox` table in the same short transaction;
- publishes from a single background publisher after commit (at-least-once; a crash between send and
  delete re-sends the same eventId and the ledger dedups by paymentId, ADR-0001);
- if a concurrent duplicate wins the insert, rolls back (which also undoes the debtor sequence
  increment, so no sequence number is spent without a payment) and returns the committed row.

Note: `payment_id` is a deterministic UUIDv5 of `client:key`, so the primary key already enforces
uniqueness in baseline; the baseline cost F-06 removes is the sequential scan of the
`(client_id, idempotency_key)` lookup.

## Client contract change
With the outbox, `POST /payments` no longer returns 503 when Kafka is down: it returns 202 and the
event is published once the broker is back. Status stays ACCEPTED until then, and the end-to-end
latency metric (from `created_at`) honestly includes the outbox delay. The 503 for a dead broker is a
baseline behaviour only. Consequences:
- phantom events disappear, so `SEQUENCE_CONFLICT` should be 0 in tuned (asserted in `OutboxTest`);
- **not implemented**: load shedding (503 + Retry-After when the outbox backlog grows). The backlog is
  exported as `gateway_outbox_backlog`; without shedding, an outage lets it grow unbounded.

## Ledger outbox is not part of F-07
The ledger already publishes through an outbox in baseline (correctness, ADR-0001). F-07's tuned fix
is the gateway outbox plus the pool sizing; the before/after comparison must attribute F-07 to the
gateway only.

## Connection pools
Baseline leaves Hikari at its default (10 connections) with far more request and consumer threads.
Tuned sets small pools for a 2-CPU Postgres shared by three services (gateway 6, validation 6, ledger
8, with a connection timeout above the worst-case unit of work). These are lab choices that have not
been swept; do not present them as optimal.

## Producer batching per service (F-01)
The gateway and ledger publish from outbox tables in batches, so they send asynchronously with a callback
and delete only acknowledged rows; `linger.ms`, a larger `batch.size` and lz4 pay off there. Validation
sends one record per listener call and must wait for the ack before the offset is committed, so it only
gets compression: lingering would add latency to every message without batching anything (its consumer
threads do not send concurrently enough to fill a batch). The F-01 commit message speaks of async sends;
that holds for the two outbox publishers, not for validation.

## Cost of the ledger lock-set fix in baseline
The drain lock-order fix (commit 711e322) adds two small queries on `pending_payments` to every baseline
record. It is a correctness fix that applies to both profiles, so it is part of the baseline cost the
before/after comparison starts from.

## Addendum: the baseline grew after the MVP
The rule "the baseline config stays untouched" held for the MVP findings. The findings marked Later (F-02, F-05, F-09, F-10,
F-11) each needed a new baseline anti-pattern, because the baseline had been quietly fixed in those respects (for example,
current Kafka clients default to idempotent producers). Each is added behind a default-off flag or an explicit
`*-baseline.yml` setting, together with its tuned fix in the same commit, and results recorded before them were re-recorded.

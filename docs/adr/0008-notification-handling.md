# ADR-0008: Notification handling (F-05)

Status: accepted

## Context
A client is told when its payment reaches a terminal state, by a call to the client's webhook. The gateway records the terminal
status from `payments.posted`. Where the webhook call happens decides how a slow or failing webhook affects everything else.

## Decision
- **Baseline (the anti-pattern).** The gateway's status consumer makes a blocking HTTP call to the webhook after the status
  update has committed, never inside a database transaction, and swallows and counts every failure. Correctness is preserved:
  a slow or failing webhook cannot lose or double a status update. Notification is at-most-once (a crash or a failed call
  loses it) and, with one consumer thread, every slow call delays the next status update.
- **Tuned.** The gateway makes no call. `notification-service` consumes `payments.posted` in its own consumer group, stores one
  row per payment (the primary key deduplicates duplicate events, and the offset is committed once the row is stored), and a
  delivery worker sends with bounded concurrency, an `Idempotency-Key` header (the payment identifier), exponential backoff and
  a DEAD state after the last attempt. Rows are claimed with a short lease so no database transaction is open during an HTTP call.
- **Delivery semantics.** Exactly-once HTTP delivery does not exist. The tuned design is at-least-once with deduplication at the
  receiver, so the effect is once. Webhooks are not ordered; there is one terminal event per payment, so that is harmless.
- **The webhook simulator** lives in the same service. Its latency and failure rates are lab parameters, changeable at run
  time. It can fail before accepting (nothing recorded) or after (recorded, then an error: the ambiguous case that makes a sender
  retry). It refuses a notification whose client does not match the endpoint it was sent to.

## Rejected
- A new topic written by the gateway: it needs either a send inside the listener or another outbox.
- A bounded in-memory executor: work is lost on a crash once the offset is committed, and a caller-runs policy brings the
  blocking back under pressure.

## Consequences
- End-to-end latency is still measured to the terminal status at the gateway. Notification delivery latency is a separate
  metric (`notifications_delivery_latency`), not part of the objective.
- Backpressure from a slow webhook shows up as table growth (`notifications_backlog`), never as consumer lag or gateway latency.
- Dead notifications need a repair process; nothing re-drives them automatically.
- The simulator's control endpoints are unauthenticated and carry the demonstration-only banner.

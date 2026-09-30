# ADR-0003: What the dashboard metrics mean (and what they must not be used for)

Status: accepted (M2)

## Decision
Service metrics feed the Grafana dashboard for live observation. They are not a source of truth for
report numbers.

- **Counters count attempts.** `payments.accepted`, `validation.results` and `ledger.applied` are
  incremented in the code path, so a transaction retry or an at-least-once redelivery counts again.
  Report numbers for throughput and outcomes come from k6 results and database row counts
  (`gateway.payments`, `ledger.ledger_payments`), never from these counters.
- **`payments.e2e.latency`** runs from the gateway row's `created_at` (Postgres `now()`, the start of
  the accepting transaction, so it includes the advisory-lock wait and the synchronous send) to the
  moment the gateway records the terminal status. It therefore includes the ledger outbox poll
  interval (250 ms). The two clocks are the Docker VM's and the host's; negative durations are
  dropped by Micrometer. It is a conservative approximation of the SPEC "POST accepted -> posted".
- **`ledger.outbox.backlog` and `ledger.pending.payments`** are gauges that run `count(*)` per scrape.
  They report NaN when the database is unreachable, not zero.
- Consumer lag comes from kafka-exporter, database figures from postgres-exporter.

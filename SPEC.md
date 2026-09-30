# SPEC — kafka-payments-perf-lab

> A realistic Spring Boot + Kafka payment pipeline, shipped in two modes — "as commonly built" and "tuned" — with load tests, dashboards, profiling evidence and a sample performance audit report.

## 1. Purpose and positioning

This is the public proof for the Upwork catalog listing "Spring Boot & Kafka architecture performance audit report" and the load-testing gig. Buyers of audits want to see *how you find problems* and *what a report looks like*. This repo shows the method end to end: baseline → measure → diagnose → fix → re-measure → report.

Important: all numbers in the README and report come from actual runs on stated hardware. Never quote production TPS figures from past employment as results of this repo.

## 2. Domain (simplified, synthetic)

A credit-transfer flow loosely modelled on ISO 20022 `pacs.008` (simplified JSON, not a compliance implementation):

```
Client ─► payment-gateway (REST) ─► [payments.initiated]
      ─► validation-service (schema, limits, sanctions-list stub) ─► [payments.validated]
      ─► ledger-service (double-entry postings in Postgres, idempotent) ─► [payments.posted]
      ─► notification-service (webhook simulator)
Status query: GET /payments/{id} via gateway (reads from ledger read model)
```

Services: `payment-gateway`, `validation-service`, `ledger-service`, `notification-service`, plus `common` module (events, serialization).

Key correctness requirements (must hold in both modes):
- Idempotency key on POST; duplicates return the original result.
- Exactly-once effect on the ledger (idempotent consumer with processed-event table, or Kafka transactions — document the choice in an ADR).
- Balances never go negative for accounts with `overdraft=false`; tested under concurrency.
- Ordering per debtor account (partition key = debtor account id).

## 3. Two modes via Spring profiles

`baseline` profile deliberately contains common real-world anti-patterns. `tuned` profile fixes them. Each anti-pattern is a numbered finding (F-01…) used in the report.

| ID | Area | Baseline (anti-pattern) | Tuned |
|---|---|---|---|
| F-01 | Producer | Synchronous `send().get()` per request, `linger.ms=0`, no compression | Async send with callback, `linger.ms` 5–20, `batch.size` raised, lz4/zstd compression |
| F-02 | Producer | `acks=all` with `max.in.flight=1` and no idempotence | Idempotent producer, `acks=all`, in-flight 5 |
| F-03 | Consumer | Concurrency 1 with 12 partitions | Concurrency matched to partitions per instance |
| F-04 | Consumer | Record-at-a-time DB writes | Batch listener + JDBC batch inserts |
| F-05 | Consumer | Blocking HTTP call (notification) inside listener | Hand-off to bounded executor / separate topic |
| F-06 | DB | Missing index on `payments(idempotency_key)` and `postings(account_id, created_at)` | Proper indexes, verified with `EXPLAIN ANALYZE` |
| F-07 | DB | Hikari pool default with far more threads than connections; long transactions wrapping Kafka sends | Pool sized to DB cores; transaction scope narrowed; outbox pattern |
| F-08 | JPA | N+1 on account + limits lookup | Fetch join / projection; limits cached (Caffeine, TTL) |
| F-09 | Serialization | Jackson ObjectMapper created per message; verbose JSON logging of full payload at INFO | Shared mapper; structured logs without payload at INFO |
| F-10 | JVM | Default heap, no GC tuning, platform threads for blocking I/O in gateway | Container-aware heap, G1/ZGC comparison, virtual threads in gateway |
| F-11 | Hot account contention | Row lock on single "settlement" account for every posting | Sharded settlement sub-accounts aggregated periodically (document trade-off) |
| F-12 | Topic/partition design | Partition key = merchant id → one large merchant creates a hot partition; 3 partitions only | Key = debtor account id, 12 partitions, sizing rationale documented (throughput per partition × target) |
| F-13 | Distributed caching | Every validation hits Postgres for account status, limits and FX rates, across multiple instances | Redis cache-aside with TTL + key versioning; Kafka `account.updated` events invalidate entries; hit-ratio metric; stampede protection (single-flight) |

## 4. Tech stack

| Concern | Choice |
|---|---|
| Services | Java 21, Spring Boot 3.x, Spring Kafka, Spring Data JPA + JdbcTemplate for batch paths |
| Broker | Kafka (KRaft) 3 brokers in compose; topics with 12 partitions, RF 3 |
| DB | PostgreSQL 16, Flyway |
| Load | k6 (primary) with scenarios; Gatling optional |
| Metrics | Micrometer → Prometheus → Grafana; kafka-exporter for consumer lag; postgres-exporter |
| Profiling | JFR recordings + async-profiler flame graphs (committed as SVG) |
| Tracing | OpenTelemetry → Jaeger (end-to-end payment trace across services) |
| Cache | Redis 7 (F-13), Caffeine for local reference data |
| Tests | JUnit 5, Testcontainers (Kafka, Postgres), concurrency tests for balance invariants |

## 5. Repository layout

```
kafka-payments-perf-lab/
├── services/{payment-gateway,validation-service,ledger-service,notification-service,common}
├── docker-compose.yml                 # 3x kafka, postgres, prometheus, grafana, jaeger, exporters
├── docker-compose.resources.yml       # fixed CPU/memory limits so runs are reproducible
├── load/k6/{smoke.js,steady.js,spike.js,soak.js}
├── scripts/run-benchmark.sh           # runs a scenario against a profile, captures metrics + JFR
├── results/<date>_<profile>_<scenario>/  # summary.json, grafana PNGs, flamegraph.svg, env.txt
├── report/
│   ├── AUDIT_REPORT_SAMPLE.md         # the sample deliverable (also exported to PDF)
│   └── template/                      # reusable report template for real clients
├── grafana/dashboards/*.json
└── docs/adr/
```

## 6. Load scenarios (k6)

| Scenario | Shape | Purpose |
|---|---|---|
| smoke | 10 VUs, 1 min | Correctness check |
| steady | Constant arrival rate, stepped: 200 → 500 → 1,000 → 2,000 req/s (adjust to hardware), 5 min each | Find max sustainable throughput at p99 SLO |
| spike | 10× burst for 60s | Backpressure and lag recovery |
| soak | 60% of max for 60 min | Memory leaks, connection exhaustion |

SLO used in the report: p99 end-to-end (POST accepted → `posted` event) under 500 ms, error rate < 0.1%, consumer lag recovers within 2 min after spike. Payload mix: 80% normal, 10% duplicate idempotency keys, 10% failing validation.

## 7. Measurements to capture per run

Throughput (req/s and events/s per topic), latency p50/p95/p99 (gateway and end-to-end), consumer lag per group, DB: TPS, lock waits, slow queries; JVM: heap, GC pause p99, thread counts; CPU per container. `run-benchmark.sh` writes `env.txt` (CPU model, cores, RAM, Docker limits, git sha) with every result.

## 8. Sample audit report (the sellable artefact)

`report/AUDIT_REPORT_SAMPLE.md` → PDF via pandoc. Sections:
1. Executive summary (one page: current capacity, target, top 3 fixes, expected gain)
2. Scope and method (environment, scenarios, SLOs, tools)
3. Architecture as found (diagram, data flow, partitioning)
4. Baseline results (tables + Grafana screenshots)
5. Findings F-01…F-11: each with Evidence (metric/flame graph/EXPLAIN), Impact, Recommendation, Effort (S/M/L), Risk
6. Prioritised remediation roadmap: impact × effort matrix (2×2 chart: quick wins, major projects, fill-ins, avoid) plus a sequenced plan (quick wins / next sprint / structural)
7. Tuned results vs baseline (before/after table)
8. Appendix: configs diff, raw data links

Also provide `report/template/` with the same structure and placeholders so it doubles as your real client deliverable template.

## 9. Milestones for Claude Code

| M | Scope | Done when |
|---|---|---|
| M0 | Multi-module build, compose with resource limits, CI | Stack starts; CI green |
| M1 | Domain + 4 services in baseline form, idempotency, ledger invariants, tests | Smoke k6 passes; invariant concurrency test passes |
| M2 | Observability: Micrometer, dashboards, exporters, tracing | One payment visible end-to-end in Jaeger; dashboards populated |
| M3 | k6 scenarios + `run-benchmark.sh` + results folder format | Baseline results captured for all scenarios |
| M4 | Tuned profile implementing F-01…F-11, each fix in a separate commit referencing its ID | Invariant tests still pass in tuned mode |
| M5 | Tuned benchmarks, JFR + flame graphs for both modes | Results captured |
| M6 | Audit report sample + template + PDF export | Report numbers match results folders |
| M7 | README with before/after table, GIF of Grafana during spike, ADRs | Portfolio checklist met |

## 10. CLAUDE.md (copy into repo root)

```markdown
# Project: kafka-payments-perf-lab
Read SPEC.md first. Implement only the requested milestone.

## Rules
- Baseline anti-patterns are intentional. Do not "fix" them outside the tuned profile.
- Every tuned change references its finding ID (F-xx) in code comments and commit message.
- Correctness first: idempotency, ledger invariants and per-account ordering must hold in both profiles.
- Never write performance numbers into docs by hand; generate from results/*.json.
- Java 21, records for events, constructor injection, Testcontainers for integration tests.

## Commands
- docker compose -f docker-compose.yml -f docker-compose.resources.yml up -d
- ./scripts/run-benchmark.sh <baseline|tuned> <smoke|steady|spike|soak>
- mvn verify
```

## 11. Additions to match the Upwork listing

The listing promises tracing root causes across **service boundaries, topic/partition design and caching strategy**, plus a roadmap **ranked by impact vs effort**. F-12, F-13 and the impact × effort matrix (§8) cover these. The report must also include an **Architecture observations** section (separate from performance findings) covering service boundaries, synchronous coupling, retry/DLQ strategy and schema evolution. That's what differentiates an *architecture* audit from a load test.

Tier alignment for sample deliverables in `sample-deliverable/`:

| Tier | Sample file | Content |
|---|---|---|
| Starter — Quick Audit (single service) | `QUICK_AUDIT_ledger-service.md` | Config + code review of one service, 5–8 findings, no load test |
| Standard | `AUDIT_REPORT_SAMPLE.md` | Full pipeline, all findings, baseline measurements |
| Advanced | `AUDIT_REPORT_SAMPLE.md` + tuned results | Before/after benchmark + roadmap |

## 12. MVP cut (tag v0.1.0)

| MVP | Later |
|---|---|
| 3 services (gateway, validation, ledger); notification stubbed | Notification service with webhook simulator |
| Single Kafka broker in compose (note RF=1 is a lab limitation) | 3-broker cluster |
| Findings F-01, F-03, F-04, F-06, F-07, F-08, F-12, F-13 | F-02, F-05, F-09, F-10, F-11 |
| k6 smoke + steady + spike | Soak, Gatling |
| Prometheus + Grafana (one dashboard) | Jaeger tracing, JFR flame graphs |
| Quick Audit sample + full report sample | PDF styling, report template folder |

MVP milestone path: M0 → M1 (3 services) → M2 (metrics only) → M3 → M4 (MVP findings) → M5 (no flame graphs) → M6 → M7.

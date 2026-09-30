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

## MVP scope
Follow the MVP cut in SPEC.md §12 (NOT the full milestone table in §9): 3 services (notification stubbed), single Kafka broker, findings F-01, F-03, F-04, F-06, F-07, F-08, F-12, F-13 only, k6 smoke/steady/spike, Prometheus + one Grafana dashboard, no Jaeger or flame graphs.
Do not implement anything marked "Later" in SPEC.md unless explicitly asked.

## Delegation
- Before building correctness-critical logic, consult the design-critic subagent.
- After finishing a milestone, run the milestone-reviewer subagent and fix every FAIL before summarising.

## Working style
- Implement ONLY the milestone named in the prompt. Stop when its acceptance criteria pass.
- Never weaken, skip or delete tests to make them pass. If a criterion seems wrong, stop and say so.
- End each milestone with: files changed, how to run/verify, anything left open.

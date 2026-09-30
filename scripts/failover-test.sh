#!/usr/bin/env bash
# Stops Kafka brokers under load and checks what the pipeline does. Usage:
#   ./scripts/failover-test.sh <baseline|tuned> <one|two>
# one: stops kafka-2 for a while, then starts it again (the cluster keeps its quorum and a full-size ISR is not needed:
#      min.insync.replicas is 2 of 3).
# two: stops kafka-2 and kafka-3 (the controller quorum and the ISR are lost), then starts them again.
# Uses a low steady load (no status polling), then asserts from result.json. The result is stored as
# results/<date>_<profile>_steady_failover-<one|two>/. Not part of `mvn verify`: it needs the compose cluster.
set -euo pipefail
PROFILE="${1:-}"; MODE="${2:-}"
case "$PROFILE" in baseline|tuned) ;; *) echo "usage: $0 <baseline|tuned> <one|two>" >&2; exit 2 ;; esac
case "$MODE" in one) STOP="kafka-2" ;; two) STOP="kafka-2 kafka-3" ;; *) echo "usage: $0 <baseline|tuned> <one|two>" >&2; exit 2 ;; esac
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
COMPOSE="docker compose -f docker-compose.yml -f docker-compose.resources.yml"

RATE="${FAILOVER_RATE:-100}"; SECONDS_LOAD="${FAILOVER_SECONDS:-90}"
BEFORE="${FAILOVER_BEFORE:-25}"; DOWN="${FAILOVER_DOWN:-25}"
export BENCH_CHAOS_CMD="sleep $BEFORE; echo stopping $STOP; $COMPOSE stop $STOP; sleep $DOWN; echo starting $STOP; $COMPOSE start $STOP"
STEPS="$RATE" STEP_SECONDS="$SECONDS_LOAD" RUN_SUFFIX="failover-$MODE" ./scripts/run-benchmark.sh "$PROFILE" steady || true

RESULT="$(ls -d results/*_"${PROFILE}"_steady_failover-"${MODE}" | tail -1)"
python3 - "$RESULT" "$PROFILE" "$MODE" <<'PY'
import json, sys
path, profile, mode = sys.argv[1:4]
r = json.load(open(f"{path}/result.json"))
inv, k6 = r["invariants"], r["k6"]
print(f"{profile} / {mode} broker(s) stopped: http failed rate {k6['http_req_failed_rate']:.4f}, "
      f"dropped iterations {k6['dropped_iterations']}, invariants hold {inv['all_hold']}, "
      f"sequence conflicts {inv['sequence_conflicts']}")
problems = []
if not inv["all_hold"]:
    problems.append("ledger invariants violated or payments not terminal after recovery")
if inv["ledger_payments_minus_gateway_payments"] != 0:
    problems.append("ledger and gateway payment counts differ")
if profile == "tuned":
    if inv["sequence_conflicts"] != 0:
        problems.append("tuned must not produce phantom events (sequence conflicts)")
    if k6["http_req_failed_rate"] != 0:
        problems.append("tuned should keep accepting payments (outbox) while brokers are down")
if problems:
    print("FAILED: " + "; ".join(problems)); sys.exit(1)
print("ok")
PY

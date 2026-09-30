#!/usr/bin/env bash
# Runs one k6 scenario against one profile from a clean state and writes results/<date>_<profile>_<scenario>/.
# Usage: ./scripts/run-benchmark.sh <baseline|tuned> <smoke|steady|spike|soak>
# Scenario knobs (env): STEPS, STEP_SECONDS (steady); BASE_RATE, WARM_SECONDS, BURST_SECONDS,
# RECOVER_SECONDS (spike); SOAK_RATE, SOAK_MINUTES (soak; the rate defaults to 60% of the profile's steady maximum,
# read from its latest steady result). They are recorded in result.json. RUN_SUFFIX appends to the results folder name.
# BENCH_CHAOS_CMD is an optional shell command run in the background during the load (fault injection).
# BENCH_SERVICE_ENV is an optional space-separated list of NAME=value settings for the services, used for ablation runs,
# for example BENCH_SERVICE_ENV=LAB_TUNING_F11=false switches one tuned finding off (Spring maps it to lab.tuning.f11).
set -euo pipefail

# A machine that sleeps mid-run produces invalid timings (and can stall the run), so hold a
# sleep assertion on macOS for the duration of the benchmark.
if [ "$(uname)" = Darwin ] && [ -z "${BENCH_CAFFEINATED:-}" ] && command -v caffeinate >/dev/null; then
  BENCH_CAFFEINATED=1 exec caffeinate -dimsu "$0" "$@"
fi

usage() { echo "usage: $0 <baseline|tuned> <smoke|steady|spike|soak>" >&2; exit 2; }
PROFILE="${1:-}"; SCENARIO="${2:-}"
case "$PROFILE" in baseline|tuned) ;; *) usage ;; esac
case "$SCENARIO" in
  smoke|steady|spike|soak) ;;
  *) usage ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
SERVICES="payment-gateway validation-service ledger-service notification-service"

if [ "$PROFILE" = tuned ]; then
  # The last M4 commit sets lab.tuning.complete=true in the gateway's tuned config. Until then a
  # tuned run would be a partial mix of baseline and tuned behaviour and must not be recorded.
  if ! grep -q 'complete: true' "services/payment-gateway/src/main/resources/payment-gateway-tuned.yml" 2>/dev/null; then
    echo "The tuned profile is not complete yet (lab.tuning.complete is not true in payment-gateway-tuned.yml)." >&2
    echo "Refusing to run so a partially tuned build cannot be recorded as tuned." >&2
    exit 2
  fi
fi

COMPOSE="docker compose -f docker-compose.yml -f docker-compose.resources.yml"
# RUN_SUFFIX distinguishes extra runs of the same scenario (for example a steady run with a higher step list).
RUN_DIR="results/$(date +%Y-%m-%d)_${PROFILE}_${SCENARIO}${RUN_SUFFIX:+_$RUN_SUFFIX}"
if [ -e "$RUN_DIR" ]; then
  echo "$RUN_DIR already exists; move or delete it first." >&2
  exit 2
fi
mkdir -p "$RUN_DIR/raw"
PIDS=""
cleanup() { for p in $PIDS; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

if [ "$SCENARIO" = soak ]; then
  SOAK_MINUTES="${SOAK_MINUTES:-60}"
  if [ -z "${SOAK_RATE:-}" ]; then
    SOAK_RATE="$(python3 - "$PROFILE" <<'PY'
import glob, json, sys
profile = sys.argv[1]
best = None
for path in sorted(glob.glob(f"results/*_{profile}_steady/result.json")):
    r = json.load(open(path))
    if r.get("max_sustainable_req_per_s"):
        best = r["max_sustainable_req_per_s"]
print(int(best * 0.6) if best else "")
PY
)"
  fi
  [ -n "$SOAK_RATE" ] || { echo "no steady result for $PROFILE to derive the soak rate from; set SOAK_RATE" >&2; exit 2; }
  export SOAK_RATE SOAK_MINUTES
  echo "soak: $SOAK_RATE req/s for $SOAK_MINUTES minutes"
fi

echo "==> build"
mvn -q -B -DskipTests package

echo "==> reset stack (volumes dropped so every run starts from the same state)"
$COMPOSE down -v --remove-orphans >/dev/null 2>&1
$COMPOSE up -d --wait --wait-timeout 240 >/dev/null

# F-10: JVM options. Baseline runs the JVM defaults. Tuned fixes the heap (no resizing, sized well above the peak seen in
# metrics.json) and picks a collector: LAB_GC=g1 (default, with a pause target) or LAB_GC=zgc (generational) for the
# G1/ZGC comparison. The services run on the host, not in containers, so this is a fixed size rather than a
# container-aware percentage. BENCH_JVM_OPTS overrides everything (an empty value forces the defaults).
LAB_GC="${LAB_GC:-g1}"
if [ "${BENCH_JVM_OPTS+set}" = set ]; then
  JVM_OPTS="$BENCH_JVM_OPTS"
elif [ "$PROFILE" = tuned ]; then
  case "$LAB_GC" in
    g1)  JVM_OPTS="-Xms512m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=50" ;;
    zgc) JVM_OPTS="-Xms512m -Xmx512m -XX:+UseZGC -XX:+ZGenerational" ;;
    *)   echo "LAB_GC must be g1 or zgc" >&2; exit 2 ;;
  esac
else
  JVM_OPTS=""
fi

echo "==> start services (profile: $PROFILE)"
for s in $SERVICES; do
  env ${BENCH_SERVICE_ENV:-} LAB_TOPIC_REPLICAS="${LAB_TOPIC_REPLICAS:-3}" \
    KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092,localhost:9094,localhost:9096}" \
    LAB_PROFILE="$PROFILE" nohup java $JVM_OPTS -jar "services/$s/target/$s-0.1.0-SNAPSHOT-exec.jar" \
    > "$RUN_DIR/raw/$s.log" 2>&1 &
  PIDS="$PIDS $!"
done
for i in $(seq 1 90); do
  ok=1
  for p in 8080 8081 8082 8083; do curl -sf "localhost:$p/actuator/health" >/dev/null || ok=0; done
  [ "$ok" = 1 ] && break
  sleep 1
done
[ "$ok" = 1 ] || { echo "services did not become healthy" >&2; exit 1; }
sleep 5 # let consumer groups finish joining

echo "==> environment"
{
  echo "profile=$PROFILE"
  echo "scenario=$SCENARIO"
  echo "git_sha=$(git rev-parse HEAD)$([ -z "$(git status --porcelain -- . ':(exclude)results')" ] || echo '-dirty')"
  echo "date_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "os=$(uname -srm)"
  if [ "$(uname)" = Darwin ]; then
    echo "cpu_model=$(sysctl -n machdep.cpu.brand_string)"
    echo "cpu_cores=$(sysctl -n hw.ncpu)"
    echo "ram_bytes=$(sysctl -n hw.memsize)"
  else
    echo "cpu_model=$(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | xargs)"
    echo "cpu_cores=$(nproc)"
    echo "ram_bytes=$(awk '/MemTotal/ {print $2*1024}' /proc/meminfo)"
  fi
  echo "jvm_opts=${JVM_OPTS:-defaults}"
  [ "$SCENARIO" = soak ] && echo "soak_rate=$SOAK_RATE soak_minutes=$SOAK_MINUTES"
  echo "ablation=${BENCH_SERVICE_ENV:-none}"
  echo "kafka_brokers=$($COMPOSE ps --services | grep -c '^kafka-[0-9]')"
  echo "webhook_latency_ms=${WEBHOOK_LATENCY_MS:-5} webhook_failure_rate=${WEBHOOK_FAILURE_RATE:-0}"
  echo "docker_vm=$(docker info --format 'cpus={{.NCPU}} mem_bytes={{.MemTotal}}')"
  echo "java=$(java -version 2>&1 | head -1)"
  echo "k6=$(k6 version | head -1)"
  echo "note=services and k6 run on the host and share CPU with the Docker VM; results are relative to this machine"
  for c in $($COMPOSE ps -q); do
    docker inspect -f '{{index .Config.Labels "com.docker.compose.service"}} {{.HostConfig.NanoCpus}} {{.HostConfig.Memory}}' "$c" \
      | awk '{printf "limit %s cpus=%.2f mem_bytes=%s\n", $1, $2/1000000000, $3}'
  done
  if [ "$PROFILE" = tuned ]; then
    echo "tuned_config:"
    for s in $SERVICES; do
      echo "  --- $s-tuned.yml"
      grep -vE '^\s*(#|$)' "services/$s/src/main/resources/$s-tuned.yml" | sed 's/^/  /'
    done
  fi
  echo "topics:"
  $COMPOSE exec -T kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --describe 2>/dev/null \
    | grep '^Topic:' | sed 's/^/  /' || true
} > "$RUN_DIR/env.txt"

echo "==> k6 $SCENARIO"
# Sample per-container CPU and memory for the whole load and drain phase (summarised into metrics.json).
# Only this compose project's containers: the machine may run unrelated ones that must not end up in results.
( while true; do docker stats --no-stream --format '{{json .}}' $($COMPOSE ps -q) >> "$RUN_DIR/raw/docker-stats.jsonl" 2>/dev/null; sleep 3; done ) &
STATS_PID=$!
PIDS="$PIDS $STATS_PID"
METRICS_START="$(python3 -c 'import time; print(time.time())')"
# Optional fault injection: BENCH_CHAOS_CMD runs in the background once the load starts (see scripts/failover-test.sh).
if [ -n "${BENCH_CHAOS_CMD:-}" ]; then
  ( bash -c "$BENCH_CHAOS_CMD" > "$RUN_DIR/raw/chaos.log" 2>&1 ) &
  PIDS="$PIDS $!"
fi
set +e
SUMMARY_PATH="$RUN_DIR/summary.json" k6 run --quiet "load/k6/$SCENARIO.js" 2>&1 | tee "$RUN_DIR/raw/k6.log"
K6_EXIT=${PIPESTATUS[0]}
set -e

echo "==> wait for the pipeline to drain"
left=1
for i in $(seq 1 300); do
  left=$($COMPOSE exec -T postgres psql -U payments -d payments -At \
    -c "select count(*) from gateway.payments where status_rank < 3")
  [ "$left" = 0 ] && break
  sleep 1
done
[ "$left" = 0 ] || echo "WARNING: $left payments still not terminal after 300 s; invariants will fail" >&2

METRICS_END="$(python3 -c 'import time; print(time.time())')"
kill "$STATS_PID" 2>/dev/null || true

echo "==> stage metrics snapshot"
python3 scripts/capture_metrics.py --run-dir "$RUN_DIR" --start "$METRICS_START" --end "$METRICS_END" \
  || echo "WARNING: could not capture the metric snapshot" >&2

echo "==> explain plans (F-06 evidence)"
./scripts/explain-analyze.sh "$RUN_DIR"

echo "==> collect"
python3 scripts/collect_results.py --run-dir "$RUN_DIR" --profile "$PROFILE" --scenario "$SCENARIO" \
  --k6-exit "$K6_EXIT"

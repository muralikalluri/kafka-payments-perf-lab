#!/usr/bin/env bash
# Runs one k6 scenario against one profile from a clean state and writes results/<date>_<profile>_<scenario>/.
# Usage: ./scripts/run-benchmark.sh <baseline|tuned> <smoke|steady|spike>
# Scenario knobs (env): STEPS, STEP_SECONDS (steady); BASE_RATE, WARM_SECONDS, BURST_SECONDS,
# RECOVER_SECONDS (spike). They are recorded in result.json.
set -euo pipefail

# A machine that sleeps mid-run produces invalid timings (and can stall the run), so hold a
# sleep assertion on macOS for the duration of the benchmark.
if [ "$(uname)" = Darwin ] && [ -z "${BENCH_CAFFEINATED:-}" ] && command -v caffeinate >/dev/null; then
  BENCH_CAFFEINATED=1 exec caffeinate -dimsu "$0" "$@"
fi

usage() { echo "usage: $0 <baseline|tuned> <smoke|steady|spike>" >&2; exit 2; }
PROFILE="${1:-}"; SCENARIO="${2:-}"
case "$PROFILE" in baseline|tuned) ;; *) usage ;; esac
case "$SCENARIO" in
  smoke|steady|spike) ;;
  soak) echo "soak is marked Later in SPEC.md §12; not implemented." >&2; exit 2 ;;
  *) usage ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
SERVICES="payment-gateway validation-service ledger-service"

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
RUN_DIR="results/$(date +%Y-%m-%d)_${PROFILE}_${SCENARIO}"
if [ -e "$RUN_DIR" ]; then
  echo "$RUN_DIR already exists; move or delete it first." >&2
  exit 2
fi
mkdir -p "$RUN_DIR/raw"
PIDS=""
cleanup() { for p in $PIDS; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

echo "==> build"
mvn -q -B -DskipTests package

echo "==> reset stack (volumes dropped so every run starts from the same state)"
$COMPOSE down -v --remove-orphans >/dev/null 2>&1
$COMPOSE up -d --wait --wait-timeout 240 >/dev/null

echo "==> start services (profile: $PROFILE)"
for s in $SERVICES; do
  LAB_PROFILE="$PROFILE" nohup java -jar "services/$s/target/$s-0.1.0-SNAPSHOT-exec.jar" \
    > "$RUN_DIR/raw/$s.log" 2>&1 &
  PIDS="$PIDS $!"
done
for i in $(seq 1 90); do
  ok=1
  for p in 8080 8081 8082; do curl -sf "localhost:$p/actuator/health" >/dev/null || ok=0; done
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
  $COMPOSE exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe 2>/dev/null \
    | grep '^Topic:' | sed 's/^/  /' || true
} > "$RUN_DIR/env.txt"

echo "==> k6 $SCENARIO"
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

echo "==> collect"
python3 scripts/collect_results.py --run-dir "$RUN_DIR" --profile "$PROFILE" --scenario "$SCENARIO" \
  --k6-exit "$K6_EXIT"

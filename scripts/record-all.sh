#!/usr/bin/env bash
# The recording protocol: every benchmark run behind the committed results, in dependency order.
#   ./scripts/record-all.sh <stage>
# Stages (run them in this order; later ones read earlier results):
#   core      smoke, steady and spike for baseline and tuned (the standard ladder)
#   variants  extended steady ladder (tuned), G1 versus ZGC, and the F-11 ablation (same ladder as the standard steady run)
#   soak      a long steady rate per profile, at 60% of that profile's steady maximum (SOAK_MINUTES, default 60)
#   failover  one and two brokers stopped under load, both profiles
#   gatling   the same workload driven by Gatling, both profiles
#   flame     JFR recordings and flame graphs at the baseline's maximum sustainable rate, both profiles
#   gif       the Grafana spike GIF (needs PUPPETEER_NODE_PATH and ffmpeg, see record-spike-gif.sh)
# Keep the working tree clean while this runs: each result records whether it was.
set -euo pipefail
cd "$(dirname "$0")/.."
STAGE="${1:-}"

steady_max() {  # <profile> -> the highest sustained offered rate from that profile's standard steady result
  python3 - "$1" <<'PY'
import glob, json, sys
for path in sorted(glob.glob(f"results/*_{sys.argv[1]}_steady/result.json")):
    print(int(json.load(open(path))["max_sustainable_req_per_s"]))
PY
}

case "$STAGE" in
  core)
    for profile in baseline tuned; do
      for scenario in smoke steady spike; do ./scripts/run-benchmark.sh "$profile" "$scenario"; done
    done ;;
  variants)
    MAX="$(steady_max tuned)"
    # Above the tuned maximum, so the ladder finds where it breaks: 1x, 1.5x, 2x and 3x, rounded to 50.
    EXTENDED_STEPS="${EXTENDED_STEPS:-$(python3 -c "m=$MAX; print(','.join(str(max(50, round(m*f/50)*50)) for f in (1,1.5,2,3)))")}"
    RUN_SUFFIX=extended STEPS="$EXTENDED_STEPS" ./scripts/run-benchmark.sh tuned steady
    LAB_GC=zgc RUN_SUFFIX=zgc ./scripts/run-benchmark.sh tuned steady
    BENCH_SERVICE_ENV=LAB_TUNING_F11=false RUN_SUFFIX=ablate-f11 ./scripts/run-benchmark.sh tuned steady ;;
  soak)
    for profile in baseline tuned; do ./scripts/run-benchmark.sh "$profile" soak; done ;;
  failover)
    for profile in baseline tuned; do
      for mode in one two; do ./scripts/failover-test.sh "$profile" "$mode" || true; done
    done ;;
  gatling)
    for profile in baseline tuned; do ./scripts/run-benchmark.sh "$profile" gatling; done ;;
  flame)
    RATE="$(steady_max baseline)"
    for profile in baseline tuned; do
      BENCH_JFR=true RUN_SUFFIX=flame STEPS="$RATE" STEP_SECONDS=120 ./scripts/run-benchmark.sh "$profile" steady
    done ;;
  gif)
    ./scripts/record-spike-gif.sh ;;
  *)
    echo "usage: $0 <core|variants|soak|failover|gatling|flame|gif>" >&2; exit 2 ;;
esac

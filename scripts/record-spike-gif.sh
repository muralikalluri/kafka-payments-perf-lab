#!/usr/bin/env bash
# Records the Grafana dashboard while the spike scenario runs against each profile (through the real
# run-benchmark.sh), then composes a side-by-side GIF: docs/img/grafana-spike.gif.
#
# Needs: ffmpeg, node, and a puppeteer install (npm install in scripts/record-grafana, or set
# PUPPETEER_NODE_PATH to an existing node_modules directory that contains puppeteer).
# The two runs are stored as results/<date>_<profile>_spike_recorded/. Screenshotting adds some load on the
# machine, so they are extra evidence and are not the runs used in the comparison tables.
# Keep the working tree clean while this runs: the results record whether it was.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PUPPETEER_NODE_PATH="${PUPPETEER_NODE_PATH:-$ROOT/scripts/record-grafana/node_modules}"
[ -d "$PUPPETEER_NODE_PATH/puppeteer" ] || { echo "puppeteer not found in $PUPPETEER_NODE_PATH" >&2; exit 2; }
command -v ffmpeg >/dev/null || { echo "ffmpeg is required" >&2; exit 2; }

SCRATCH="${SCRATCH:-$(mktemp -d)}"
CAPTURE_SECONDS="${CAPTURE_SECONDS:-240}"   # warm + burst + recovery phases plus a tail
INTERVAL="${INTERVAL:-3}"
echo "scratch: $SCRATCH"

for profile in baseline tuned; do
  echo "==> recording $profile"
  RUN_SUFFIX=recorded ./scripts/run-benchmark.sh "$profile" spike > "$SCRATCH/$profile.log" 2>&1 &
  run_pid=$!
  until grep -q "==> k6 spike" "$SCRATCH/$profile.log" 2>/dev/null; do
    kill -0 "$run_pid" 2>/dev/null || { echo "benchmark exited early:" >&2; tail -20 "$SCRATCH/$profile.log" >&2; exit 1; }
    sleep 1
  done
  NODE_PATH="$PUPPETEER_NODE_PATH" node scripts/record-grafana/record.js \
    "$SCRATCH/frames-$profile" "$profile" "$CAPTURE_SECONDS" "$INTERVAL"
  wait "$run_pid"
done

echo "==> composing docs/img/grafana-spike.gif"
mkdir -p docs/img
ffmpeg -y -loglevel error \
  -framerate 4 -i "$SCRATCH/frames-baseline/frame_%04d.png" \
  -framerate 4 -i "$SCRATCH/frames-tuned/frame_%04d.png" \
  -filter_complex "[0:v]scale=600:-1[a];[1:v]scale=600:-1[b];[a][b]hstack=inputs=2:shortest=1,split[s0][s1];[s0]palettegen=max_colors=96[p];[s1][p]paletteuse=dither=bayer:bayer_scale=4" \
  -loop 0 docs/img/grafana-spike.gif
ls -la docs/img/grafana-spike.gif

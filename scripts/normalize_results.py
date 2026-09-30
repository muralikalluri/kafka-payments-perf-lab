#!/usr/bin/env python3
"""Brings result.json files recorded before a collector fix up to the current result schema.

The only migration so far: an unrecovered spike used to be written as recovery_seconds_after_burst =
<length of the observation window>, which looks like a measurement. The collector now writes null there
and keeps the observed lower bound as recovery_at_least_seconds. Results recorded earlier (the database
they came from no longer exists, so they cannot be re-collected) are updated the same way. The change is
recorded inside the file under result_migrations; no measured value is altered. Idempotent.

Usage: normalize_results.py [results-dir]
"""
import glob
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


def migrate(result: dict) -> bool:
    spike = result.get("spike")
    if not spike or spike.get("recovered_within_run") or "recovery_at_least_seconds" in spike:
        return False
    spike["recovery_at_least_seconds"] = spike["recovery_seconds_after_burst"]
    spike["recovery_seconds_after_burst"] = None
    result.setdefault("result_migrations", []).append({
        "script": "scripts/normalize_results.py",
        "change": "recovery_seconds_after_burst set to null because the spike had not recovered within the run; "
                  "the former value is kept as recovery_at_least_seconds",
        "reason": "the collector was changed to record an unrecovered spike this way after this run was recorded",
    })
    return True


def main() -> int:
    results_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "results")
    changed = []
    for path in sorted(glob.glob(os.path.join(results_dir, "*", "result.json"))):
        with open(path) as fh:
            result = json.load(fh)
        if migrate(result):
            with open(path, "w") as fh:
                json.dump(result, fh, indent=2)
                fh.write("\n")
            changed.append(os.path.relpath(path, ROOT))
    print("migrated: " + (", ".join(changed) if changed else "nothing to do"))
    return 0


if __name__ == "__main__":
    sys.exit(main())

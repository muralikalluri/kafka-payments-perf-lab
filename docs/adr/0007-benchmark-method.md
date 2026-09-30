# ADR-0007: Benchmark method and result format

Status: accepted (M3, M5)

## Decision
- **Clean state per run.** `scripts/run-benchmark.sh` drops the stack's volumes, restarts the services under the
  profile being measured and refuses to overwrite an existing result folder, so every run starts from the same
  database and topics. The machine is kept awake for the duration (a sleeping laptop invalidated an early run).
- **Latency comes from the database, not the services.** End-to-end latency is `updated_at - created_at` of the
  gateway row (ADR-0003 explains what that includes). k6 supplies only the request-side figures.
- **Stepped load with recorded step lists.** The steady scenario offers constant arrival rates in steps; the step
  list and step length are parameters recorded in each `result.json`. A step meets the objective only if POST p99,
  end-to-end p99, error rate and dropped iterations all pass. Limits are therefore known to step resolution.
- **Invariants are part of the result.** After every run the collector checks that no balance is negative, debits
  equal credits, total money is conserved, nothing is pending or in an outbox, and every payment is terminal. A run
  that violates them exits non-zero.
- **Provenance.** Each result records the git revision and whether the tree was dirty, the machine and container
  limits, and (for tuned) the tuned configuration. Extra runs use `RUN_SUFFIX`; nothing is deleted to look better,
  and runs that were replaced (an earlier baseline recording) remain in the git history.
- **Everything published is derived.** Reports and the README are generated from `results/*` by
  `scripts/generate_report.py`; a lint rejects numbers in the template prose and CI fails when a committed
  document differs from a fresh generation.

## Consequences
- The method is honest but coarse: one machine, load generator on the same host, one run per point.
- The first step of a run pays for warm-up (JIT, pools, caches); runs whose first step missed the objective are
  reported as such rather than dropped.
- Migrating an old result file to a corrected schema is done by a script that records the change in the file
  (`scripts/normalize_results.py`); measured values are never edited.

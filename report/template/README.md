# Report template

Fill-in versions of the two deliverables, with the same structure as the samples in `sample-deliverable/` so a real client
report can be produced quickly and consistently.

| Tier | Use | Sample to compare against |
|---|---|---|
| Starter: quick audit of one service | `QUICK_AUDIT_TEMPLATE.md` (configuration and code review, no load test) | `sample-deliverable/QUICK_AUDIT_ledger-service.md` |
| Standard: full pipeline audit with baseline measurements | `REPORT_TEMPLATE.md`, sections 1 to 7 and 9 | `sample-deliverable/AUDIT_REPORT_SAMPLE.md` |
| Advanced: standard plus a tuned build and a before/after benchmark | `REPORT_TEMPLATE.md`, all sections | the same sample |

## How to use it

1. Copy the template into the client's folder and replace every `[bracketed placeholder]`. Delete guidance lines
   (the ones starting with `> Guidance:`) before delivery.
2. Take every figure from a results file you can attach (`result.json`, `env.txt`, plan output, metric snapshots). If a
   number is not in a file, it does not go in the report. Cite the run folder next to each table.
3. Export to PDF with `node scripts/export_pdf.js <report.md>` (needs pandoc and puppeteer, see the script header).

## Rules the samples follow (keep them)

- **Say what was measured, and how coarsely.** A stepped load test only brackets a limit between two steps; report the
  bracket, not a single "N times faster".
- **Only claim what the data isolates.** If all changes were applied together, say the effect of each was not isolated
  and recommend an ablation (one change reverted) before large investments.
- **Do not name a bottleneck without stage evidence.** Consumer lag, CPU, garbage collection, connection pool use and lock
  waits per stage are the evidence; without them, describe mechanisms, not culprits.
- **Report failed and awkward runs.** Runs discarded (and why), first steps that missed the objective (warm-up), and
  recordings that disagree belong in the method section, not in a drawer.
- **Label assessments as assessments.** Impact scores are judgements from the mechanism and the measurement; effort and
  risk are estimates. Put measured facts and judgements in different places.
- **State the trade-offs.** A change that raises latency at low load, adds staleness, or changes a client contract is a
  trade-off the client must accept explicitly.
- **Keep the client's data out of examples** and never quote production figures from other engagements.

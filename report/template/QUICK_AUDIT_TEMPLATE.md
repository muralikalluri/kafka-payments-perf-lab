# Quick audit: [SERVICE NAME]

**Client:** [CLIENT NAME]. **Service reviewed:** [service and one line on what it does]. **Prepared:** [date].

> Guidance: this is the Starter tier: a configuration and code review of one service. No load test is run for it, so the
> document contains no measured performance figures; values quoted are read from the service's own configuration.

## Scope and method

[What was read (code, configuration, schema), what path was traced, and what was looked for: cost that grows with volume,
contention that limits concurrency, and failure behaviour that loses or stalls work. Correctness properties are checked first
and are not the subject of the findings.]

## Findings

> Guidance: five to eight findings, ordered by expected value. Each has the same four parts.

### Q-A [Short title]

- **Observation.** [What the code or configuration does, with the file or setting.]
- **Impact.** [What it costs, and that it is expected or assessed, not measured.]
- **Recommendation.** [The change.]
- **Effort.** [S, M or L.] **Risk.** [Low, Medium or High, and why.]

## Suggested order

[The sequence to do them in and any dependency between them.]

## What a full audit would add

[A load test against the objective, per-stage measurements, an ablation of each change, and the neighbouring services.]

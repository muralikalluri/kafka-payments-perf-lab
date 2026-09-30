# ADR-0011: Tracing, profiling and soak tooling

Status: accepted

## Tracing
- OpenTelemetry through Micrometer Tracing, exported over OTLP to Jaeger. Kafka producers and consumers propagate the trace with
  the standard W3C `traceparent` header.
- **Sampling is off by default** (`TRACING_SAMPLING`), so benchmark results are untraced; tests and demos turn it on.
- **Outboxes break traces unless helped.** A publisher runs later on another thread with no current span. Both outbox tables
  store the traceparent of the writing span, and the publisher restores it as the parent of a short span around the send.
- **Batch consumers have one span for many payments.** The ledger's batch consumer therefore writes each outcome under a short
  `ledger.apply` span that is a child of that payment's own trace (from the record's `traceparent` header).
- Scheduled polling tasks are excluded from tracing; otherwise the publishers' polls drown the payment traces.
- `TracingTest` (opt-in, `-Dlab.tracing=true`, starts a Jaeger container) asserts one payment is one trace across the gateway,
  validation and ledger, in both profiles.

## Profiling
- `BENCH_JFR=true` records every service with Java Flight Recorder and dumps at the end of the load; `scripts/flamegraph.py`
  turns each recording into CPU and native-call flame graphs plus a top-frames summary. Raw recordings are not committed.
- JFR samples running threads on an interval, so the graphs show where sampled time went, not everything a thread waited on.

## Soak
- The soak scenario runs a steady rate for a long time at a share of the profile's own maximum sustainable rate. The collector
  reports latency in consecutive windows and the growth of heap, threads and pool use between the first and last quarter of the
  run. A single soak run is an observation, not proof that there are no leaks.

## Stage metrics
- Every run stores a `metrics.json` (consumer lag, JVM heap and GC, CPU, threads, pools, database locks and commits, outbox
  backlogs, and per-container CPU and memory for this project's containers only) so a result can be explained, and so no
  bottleneck is named without evidence.

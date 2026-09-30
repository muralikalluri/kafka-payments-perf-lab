# ADR-0010: Three-broker cluster and behaviour when brokers stop

Status: accepted

## Decision
- **Cluster.** Three KRaft nodes, each both broker and controller, replication factor 3, `min.insync.replicas` 2, producers with
  `acks=all`. Host JVMs use the EXTERNAL listeners; containers (the exporter, inter-broker traffic) use INTERNAL. Broker memory is
  limited and the heap set explicitly, and the broker count is recorded in each result's `env.txt`.
- **Replication is a property of the environment, not of the profile.** Topics take `lab.topics.replicas` (and a matching
  `min.insync.replicas`); the single-broker Testcontainers suite keeps 1, the compose cluster runs 3 via the runner.
- **Idempotence is a throughput setting, not a correctness one (F-02).** Consumers deduplicate in every profile, because
  idempotence only suppresses retries within one producer session; outbox resends after a crash, redeliveries and new producer
  sessions still produce duplicates.
- **Failover tests** (`scripts/failover-test.sh`) stop one broker, or two, under load and restart them. They are not part of
  `mvn verify`. They assert what must hold in both profiles (ledger invariants, ledger and gateway counts agree, every payment
  terminal after recovery) and, for the tuned profile, that requests keep being accepted (the outbox absorbs the outage) and
  no phantom events appear.

## What to expect (and what the tests show)
- One broker stopped: the quorum and the in-sync replica minimum still hold; the effect is transient latency.
- Two brokers stopped: the controller quorum is lost as well, because the nodes are combined. Producers time out rather than
  receiving a "not enough replicas" error. The baseline gateway, which publishes inside the request, rejects requests; the tuned
  gateway keeps accepting and its outbox backlog grows until the brokers return, then drains.
- Do not assert a particular error type for the two-broker case: which failure surfaces first depends on timing.

## Limits
A cluster on one machine approximates broker-level effects (network replication cost is missing). The database is a single
instance; its failover is not tested.

# ADR-0006: Distributed account cache (F-13)

Status: accepted (M4)

## Decision
Tuned validation reads account data (ownership, currency, status, per-transaction limit) through a
Redis cache-aside layer instead of querying Postgres for every payment.

- **Layering.** Accounts live in Redis so that several validation instances share one cache and
  `account.updated` events can invalidate it. FX rates stay in the F-08 Caffeine cache (tiny, static, no
  invalidation event) rather than being cached twice. SPEC §3 lists FX under Redis; this is a deliberate,
  documented deviation.
- **Key versioning.** Every entry carries the account's `version`, a per-account counter incremented by
  every change (`validation.accounts.version`). A namespace prefix (`lab.cache.namespace`) lets an
  operator drop every entry by bumping it.
- **Invalidation.** `account.updated {accountId, version, status}` is published after a change and
  consumed by every instance (each with its own consumer group). The consumer writes a tombstone
  carrying the version instead of deleting the key, using a compare-and-set Lua script. The populate
  path uses the mirror script. Together they prevent the classic race: a slow reader that loaded
  version 1 cannot overwrite version 2 after the invalidation, and a late or repeated event cannot evict
  newer data.
- **Stampede protection.** One caller per key loads from Postgres (single-flight); the leader re-checks
  the cache first, so a burst of misses costs one database read.
- **Failure behaviour.** Redis errors fail open to Postgres, with a 100 ms command timeout and a small
  circuit breaker. Invalidation ignores the breaker (a lost invalidation is the dangerous failure) and a
  failed one is counted (`cache_invalidations_total{result="error"}`), not retried; the TTL is the
  backstop.
- **Never cached.** "Account not found". Ownership (`DEBTOR_NOT_OWNED`) is always evaluated from the
  cached `client_id`.
- **Metrics.** `cache_gets_total{layer,result}`, `cache_loads_total`, `cache_invalidations_total`,
  `cache_singleflight_waits_total`; the dashboard shows the hit ratio.

## Accepted staleness
Only validation checks account status; the ledger has no status column. So a blocked account can still
be approved until its cached copy is invalidated or expires: bounded by the event latency in the normal
case and by the TTL (60 s by default) when an event is lost. This risk is accepted for the lab and must
be stated in any report; a real system would decide its own tolerance (or verify status again at
posting time).

## Lab-only admin endpoint
`PUT /lab-admin/accounts/{id}` changes a status and publishes the event. It only exists when
`lab.tuning.f13` is on, requires a shared token (placeholder in `.env.example`) and shares the service's
port; it is not part of the payment API and not a model for production authentication.

## Dependencies
`lab.tuning.f13` requires `lab.tuning.f08` (the cache loads from the F-08 projection source); enabling F-13
alone fails at startup.

## Transactions
Baseline wraps a whole validation in one read-only transaction (JPA lazy loading needs it). Tuned uses
plain JDBC and no wrapping transaction, otherwise a pooled connection would be held even on a cache hit.

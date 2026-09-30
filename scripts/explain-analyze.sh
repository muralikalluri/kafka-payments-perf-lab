#!/usr/bin/env bash
# Captures EXPLAIN (ANALYZE, BUFFERS) for the two queries behind finding F-06 against the database as
# left by a benchmark run. Usage: ./scripts/explain-analyze.sh <results-run-dir>
# Only meaningful after a run: on an empty database every plan is trivially fast.
set -euo pipefail
RUN_DIR="${1:?usage: $0 <results-run-dir>}"
cd "$(dirname "$0")/.."
COMPOSE="docker compose -f docker-compose.yml -f docker-compose.resources.yml"
psql() { $COMPOSE exec -T postgres psql -U payments -d payments -At "$@"; }

KEY=$(psql -c "select idempotency_key from gateway.payments order by created_at desc limit 1")
ACCOUNT=$(psql -c "select debtor_account_id from gateway.payments group by 1 order by count(*) desc limit 1")
{
  echo "rows: gateway.payments=$(psql -c 'select count(*) from gateway.payments') ledger.postings=$(psql -c 'select count(*) from ledger.postings')"
  echo "indexes on gateway.payments:"; psql -c "select '  ' || indexname from pg_indexes where schemaname='gateway' and tablename='payments' order by 1"
  echo "indexes on ledger.postings:";  psql -c "select '  ' || indexname from pg_indexes where schemaname='ledger' and tablename='postings' order by 1"
  echo
  echo "== F-06 idempotency lookup (gateway PaymentService.find)"
  psql -c "EXPLAIN (ANALYZE, BUFFERS) SELECT request_hash, response_body FROM gateway.payments WHERE client_id = 'client-demo' AND idempotency_key = '$KEY'"
  echo
  echo "== F-06 daily outflow (ledger LedgerService.outflowToday) for the busiest debtor $ACCOUNT"
  psql -c "EXPLAIN (ANALYZE, BUFFERS) SELECT COALESCE(SUM(amount_minor), 0) FROM ledger.postings WHERE account_id = '$ACCOUNT' AND direction = 'D' AND created_at >= date_trunc('day', now())"
} > "$RUN_DIR/explain.txt"
echo "wrote $RUN_DIR/explain.txt"

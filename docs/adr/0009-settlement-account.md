# ADR-0009: Settlement account and its sharding (F-11)

Status: accepted

## Context
Real ledgers route customer payments through a settlement (transit) account. A single settlement account is a hot row: every
posting takes a row lock on it, so payments serialise once more than one consumer thread applies them.

## Decision
- **Transit legs.** Each applied payment posts four entries in one transaction: debit debtor, credit settlement, debit
  settlement, credit creditor. Settlement nets to zero at every commit, so customer balances, the money-conservation check and
  the balance rules are unchanged. The credit to settlement is posted before its debit, because the balance check constraint is
  evaluated per statement and settlement accounts are not allowed to go negative.
- **Baseline** uses one settlement account per currency for every posting. **Tuned** uses a set of shard accounts and picks one
  per transaction from the source partition. The choice is per transaction, not per payment: hashing per payment would make
  every batch lock every shard and remove the benefit. Because the legs net to zero inside the transaction, the choice needs no
  coordination and does not have to be repeatable across redeliveries.
- **Lock order.** The shard is added to the same single ordered `SELECT ... FOR UPDATE` as the debtor, the creditor and the
  creditors of parked payments, in both the record-at-a-time and the batch path. The global lock order is unchanged, so no new
  deadlock is possible. Parked payments drained in the same transaction use that transaction's shard.
- **Reserved accounts.** Settlement accounts can never be a debtor or creditor. A reserved debtor is rejected outright; a reserved
  creditor goes through the normal sequence flow and is rejected there, so its debtor's sequence number is consumed and the
  debtor is not stalled. A currency with no settlement account is rejected (`SETTLEMENT_ACCOUNT_MISSING`).
- **Reconciliation** is a periodic job that records the aggregate position and how many settlement accounts are not zero, and
  warns if any is. It moves no money.

## Rejected
- A fee leg credited to settlement, swept periodically into a parent account. It makes "aggregation" real but changes the funds
  check (amount plus fee) and every balance assertion. Chosen against by the project owner.

## Consequences
- Postings per payment double, which raises the baseline cost of the postings scans (F-06) and of batch writes (F-04).
- Results recorded before this change are not comparable and were re-recorded.
- The baseline cannot show this finding, because it has one consumer thread. The evidence is an ablation: the tuned profile with
  only this change switched off.

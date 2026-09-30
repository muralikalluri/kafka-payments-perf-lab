package lab.payments.ledgerservice;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lab.payments.common.TraceCarrier;
import org.springframework.beans.factory.ObjectProvider;
import java.sql.Array;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lab.payments.common.Ids;
import lab.payments.common.Json;
import lab.payments.common.PaymentPosted;
import lab.payments.common.PaymentValidated;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies validated payments exactly once, in debtor-sequence order.
 *
 * Invariants (ADR-0001, ADR-0002):
 *  - dedup: ledger_payments.payment_id is written in the same transaction as the postings;
 *  - the debtor, creditor and settlement accounts (and the creditors of parked payments) are row-locked in id
 *    order, so balances never go negative and A->B / B->A
 *    cannot deadlock;
 *  - a payment is applied only when debtor_seq == account.last_seq + 1; early arrivals are parked
 *    in pending_payments and drained in order, so per-debtor order holds even though baseline
 *    keys records by merchant (F-12).
 *
 * F-04 (baseline, intentional): one record, one transaction, one round trip per statement.
 */
@Service
public class LedgerService {

    private final JdbcTemplate jdbc;

    private final MeterRegistry meters;
    private final boolean keyByDebtor;
    private final SettlementAccounts settlement;
    private final Tracer tracer;
    private final Propagator propagator;

    public LedgerService(JdbcTemplate jdbc, MeterRegistry meters,
            @Value("${lab.tuning.f12:false}") boolean keyByDebtor, SettlementAccounts settlement,
            ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer.getIfAvailable();
        this.propagator = propagator.getIfAvailable();
        this.keyByDebtor = keyByDebtor;
        this.settlement = settlement;
        this.jdbc = jdbc;
        this.meters = meters;
        meters.counter("ledger.parked"); // export 0 from startup so dashboards show a series
    }

    private record Account(String id, String currency, boolean overdraft, long balance,
            long dailyLimit, long lastSeq) {
    }

    /** A parked payment's creditor appeared after the lock set was chosen; retry with a larger set. */
    static class LockSetChangedException extends RuntimeException {
        LockSetChangedException() {
            super("pending payments changed while locking; retrying with a larger lock set");
        }
    }

    /** @param shardHint the source partition: it picks the settlement shard when F-11 is on */
    @Transactional
    public void handle(PaymentValidated e, int shardHint) {
        lockDebtorSet(e.debtorAccountId(), e.creditorAccountId(), e.currency(), shardHint);
        if (exists(e)) {
            // Duplicate delivery: outcome already recorded and queued in the outbox. It may still
            // carry a fresh sequence number (client retry after a failed gateway commit), which
            // must be consumed or the debtor's later payments would park forever.
            republishRecordedOutcome(e);
            sequenceForDuplicate(e, shardHint);
            return;
        }
        if (SettlementAccounts.isReserved(e.debtorAccountId())) {
            // Not a customer account: it has no sequence to consume, so it can be rejected outright. (A reserved
            // CREDITOR must go through the normal sequence flow, or its debtor would wait for the number forever.)
            record(e, PaymentPosted.Outcome.REJECTED, "RESERVED_ACCOUNT");
            return;
        }
        Account debtor = account(e.debtorAccountId());
        if (debtor == null) {
            record(e, PaymentPosted.Outcome.REJECTED, "DEBTOR_NOT_FOUND");
            return;
        }
        long expected = debtor.lastSeq() + 1;
        if (e.debtorSeq() > expected) {
            park(e);
            return;
        }
        if (e.debtorSeq() < expected) {
            // Unknown payment claiming an already-consumed sequence (a phantom from a failed
            // gateway commit). Reject without touching the sequence.
            record(e, PaymentPosted.Outcome.REJECTED, "SEQUENCE_CONFLICT");
            return;
        }
        apply(e, shardHint);
        drain(e.debtorAccountId(), shardHint);
    }

    private void sequenceForDuplicate(PaymentValidated e, int shardHint) {
        Account debtor = account(e.debtorAccountId());
        if (debtor == null) {
            return;
        }
        long expected = debtor.lastSeq() + 1;
        if (e.debtorSeq() == expected) {
            jdbc.update("UPDATE accounts SET last_seq = ? WHERE id = ?", e.debtorSeq(), debtor.id());
            drain(debtor.id(), shardHint);
        } else if (e.debtorSeq() > expected) {
            park(e);
        } // else: plain redelivery of an already-sequenced event, nothing to do
    }

    private void apply(PaymentValidated e, int shardHint) {
        Account debtor = account(e.debtorAccountId());
        Account creditor = account(e.creditorAccountId());
        String settleId = settlement.id(e.currency(), shardHint);
        Account settle = account(settleId);
        String reason = null;
        if (e.outcome() == PaymentValidated.Outcome.REJECTED) {
            reason = e.reasonCode();
        } else if (creditor == null) {
            reason = "CREDITOR_NOT_FOUND";
        } else if (SettlementAccounts.isReserved(creditor.id())) {
            reason = "RESERVED_ACCOUNT";
        } else if (debtor.id().equals(creditor.id())) {
            reason = "SAME_ACCOUNT";
        } else if (!debtor.currency().equals(e.currency()) || !creditor.currency().equals(e.currency())) {
            reason = "CURRENCY_MISMATCH";
        } else if (settle == null) {
            reason = "SETTLEMENT_ACCOUNT_MISSING";
        } else if (!debtor.overdraft() && debtor.balance() < e.amountMinor()) {
            reason = "INSUFFICIENT_FUNDS";
        } else if (outflowToday(debtor.id()) + e.amountMinor() > debtor.dailyLimit()) {
            reason = "DAILY_LIMIT_EXCEEDED";
        }
        record(e, reason == null ? PaymentPosted.Outcome.POSTED : PaymentPosted.Outcome.REJECTED, reason);
        if (reason == null) {
            // Four legs through settlement (F-11): debtor -> settlement -> creditor. The settlement credit is posted
            // before its debit so the balance never dips below zero (the CHECK constraint is per statement).
            post(e, debtor.id(), "D");
            post(e, settleId, "C");
            post(e, settleId, "D");
            post(e, creditor.id(), "C");
            jdbc.update("UPDATE accounts SET balance_minor = balance_minor - ? WHERE id = ?", e.amountMinor(), debtor.id());
            jdbc.update("UPDATE accounts SET balance_minor = balance_minor + ? WHERE id = ?", e.amountMinor(), settleId);
            jdbc.update("UPDATE accounts SET balance_minor = balance_minor - ? WHERE id = ?", e.amountMinor(), settleId);
            jdbc.update("UPDATE accounts SET balance_minor = balance_minor + ? WHERE id = ?", e.amountMinor(), creditor.id());
        }
        jdbc.update("UPDATE accounts SET last_seq = ? WHERE id = ?", e.debtorSeq(), debtor.id());
    }

    private void post(PaymentValidated e, String accountId, String direction) {
        jdbc.update("INSERT INTO postings(payment_id, account_id, direction, amount_minor) VALUES (?,?,?,?)",
                e.paymentId(), accountId, direction, e.amountMinor());
    }

    private void drain(String debtorId, int shardHint) {
        while (true) {
            long next = account(debtorId).lastSeq() + 1;
            List<String> parked = jdbc.query(
                    "SELECT payload FROM pending_payments WHERE debtor_account_id = ? AND debtor_seq = ?",
                    (rs, i) -> rs.getString(1), debtorId, next);
            if (parked.isEmpty()) {
                return;
            }
            jdbc.update("DELETE FROM pending_payments WHERE debtor_account_id = ? AND debtor_seq = ?",
                    debtorId, next);
            PaymentValidated pe = Json.read(parked.get(0), PaymentValidated.class);
            // No locking here: every creditor of a parked payment is already in the lock set.
            if (exists(pe)) {
                jdbc.update("UPDATE accounts SET last_seq = ? WHERE id = ?", pe.debtorSeq(), debtorId);
            } else {
                apply(pe, shardHint);
            }
        }
    }

    private void park(PaymentValidated e) {
        int inserted = jdbc.update("""
                INSERT INTO pending_payments(debtor_account_id, debtor_seq, payload) VALUES (?,?,?)
                ON CONFLICT DO NOTHING""", e.debtorAccountId(), e.debtorSeq(), Json.write(e));
        if (inserted > 0) {
            meters.counter("ledger.parked").increment();
        }
    }

    private void record(PaymentValidated e, PaymentPosted.Outcome outcome, String reason) {
        jdbc.update("""
                INSERT INTO ledger_payments(payment_id, debtor_account_id, debtor_seq, outcome, reason_code)
                VALUES (?,?,?,?,?)""",
                e.paymentId(), e.debtorAccountId(), e.debtorSeq(), outcome.name(), reason);
        enqueuePosted(e, outcome, reason);
        meters.counter("ledger.applied", "outcome", outcome.name()).increment();
    }

    /** Writes the outcome event to the outbox; the publisher sends it after the transaction commits. */
    private void enqueuePosted(PaymentValidated e, PaymentPosted.Outcome outcome, String reason) {
        PaymentPosted posted = new PaymentPosted(PaymentPosted.SCHEMA_VERSION,
                Ids.eventId(e.paymentId(), "posted"), e.eventId(), e.paymentId(), e.clientId(),
                e.merchantId(), e.debtorAccountId(), outcome, reason, Instant.now());
        // F-12: keyed by merchant id in baseline, by debtor account id in tuned.
        jdbc.update("INSERT INTO outbox(msg_key, payload, trace) VALUES (?,?,?)",
                keyByDebtor ? e.debtorAccountId() : e.merchantId(), Json.write(posted),
                TraceCarrier.capture(tracer, propagator));
    }

    /**
     * A payment seen again is already recorded, but its outcome may not have reached the gateway's row: the first
     * outcome can arrive before the row exists (a phantom event from a gateway send whose transaction rolled back, then
     * the client's retry). The gateway's status update is idempotent and monotonic, so re-publishing the recorded
     * outcome is safe, and without it that payment's status could stay ACCEPTED for good.
     */
    private void republishRecordedOutcome(PaymentValidated e) {
        jdbc.query("SELECT outcome, reason_code FROM ledger_payments WHERE payment_id = ?", rs -> {
            enqueuePosted(e, PaymentPosted.Outcome.valueOf(rs.getString(1)), rs.getString(2));
            meters.counter("ledger.republished").increment();
        }, e.paymentId());
    }

    private boolean exists(PaymentValidated e) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM ledger_payments WHERE payment_id = ?",
                Integer.class, e.paymentId());
        return n != null && n > 0;
    }

    /**
     * Locks the debtor, the creditor and the creditors of every payment parked for this debtor, in
     * one statement ordered by id. Draining parked payments then never takes a lock out of order,
     * so opposite transfers and drains cannot deadlock with each other. The parked set can only
     * change under the debtor's lock, so it is re-read once the lock is held.
     */
    private void lockDebtorSet(String debtor, String creditor, String currency, int shardHint) {
        // Not Set.of: the ids can coincide (same account twice, or a reserved settlement account), and Set.of rejects duplicates.
        Set<String> ids = new TreeSet<>();
        ids.add(debtor);
        ids.add(creditor);
        ids.add(settlement.id(currency, shardHint));
        ids.addAll(pendingLockIds(debtor, shardHint));
        lockAll(ids);
        if (!ids.containsAll(pendingLockIds(debtor, shardHint))) {
            throw new LockSetChangedException();
        }
    }

    /** Creditors and settlement accounts that draining this debtor's parked payments would need. */
    private Set<String> pendingLockIds(String debtor, int shardHint) {
        Set<String> ids = new TreeSet<>();
        for (String payload : jdbc.query(
                "SELECT payload FROM pending_payments WHERE debtor_account_id = ?",
                (rs, i) -> rs.getString(1), debtor)) {
            PaymentValidated parked = Json.read(payload, PaymentValidated.class);
            ids.add(parked.creditorAccountId());
            ids.add(settlement.id(parked.currency(), shardHint));
        }
        return ids;
    }

    private void lockAll(Set<String> ids) {
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT id FROM accounts WHERE id = ANY(?) ORDER BY id FOR UPDATE");
            Array array = con.createArrayOf("text", ids.toArray());
            ps.setArray(1, array);
            return ps;
        }, rs -> { });
    }

    private Account account(String id) {
        return jdbc.query("""
                SELECT id, currency, overdraft, balance_minor, daily_limit_minor, last_seq
                FROM accounts WHERE id = ? FOR UPDATE""",
                (rs, i) -> new Account(rs.getString("id"), rs.getString("currency").trim(),
                        rs.getBoolean("overdraft"), rs.getLong("balance_minor"),
                        rs.getLong("daily_limit_minor"), rs.getLong("last_seq")),
                id).stream().findFirst().orElse(null);
    }

    private long outflowToday(String accountId) {
        // F-06: sequential scan of postings (no index on account_id, created_at).
        Long sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount_minor), 0) FROM postings
                WHERE account_id = ? AND direction = 'D' AND created_at >= date_trunc('day', now())""",
                Long.class, accountId);
        return sum == null ? 0 : sum;
    }
}

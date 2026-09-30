package lab.payments.ledgerservice;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import lab.payments.common.Ids;
import lab.payments.common.Json;
import lab.payments.common.PaymentPosted;
import lab.payments.common.PaymentValidated;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies validated payments exactly once, in debtor-sequence order.
 *
 * Invariants (ADR-0001, ADR-0002):
 *  - dedup: ledger_payments.payment_id is written in the same transaction as the postings;
 *  - both accounts are row-locked in id order, so balances never go negative and A->B / B->A
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

    public LedgerService(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
        meters.counter("ledger.parked"); // export 0 from startup so dashboards show a series
    }

    private record Account(String id, String currency, boolean overdraft, long balance,
            long dailyLimit, long lastSeq) {
    }

    @Transactional
    public void handle(PaymentValidated e) {
        lockOrdered(e.debtorAccountId(), e.creditorAccountId());
        if (exists(e)) {
            // Duplicate delivery: outcome already recorded and queued in the outbox. It may still
            // carry a fresh sequence number (client retry after a failed gateway commit), which
            // must be consumed or the debtor's later payments would park forever.
            sequenceForDuplicate(e);
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
        apply(e);
        drain(e.debtorAccountId());
    }

    private void sequenceForDuplicate(PaymentValidated e) {
        Account debtor = account(e.debtorAccountId());
        if (debtor == null) {
            return;
        }
        long expected = debtor.lastSeq() + 1;
        if (e.debtorSeq() == expected) {
            jdbc.update("UPDATE accounts SET last_seq = ? WHERE id = ?", e.debtorSeq(), debtor.id());
            drain(debtor.id());
        } else if (e.debtorSeq() > expected) {
            park(e);
        } // else: plain redelivery of an already-sequenced event, nothing to do
    }

    private void apply(PaymentValidated e) {
        Account debtor = account(e.debtorAccountId());
        Account creditor = account(e.creditorAccountId());
        String reason = null;
        if (e.outcome() == PaymentValidated.Outcome.REJECTED) {
            reason = e.reasonCode();
        } else if (creditor == null) {
            reason = "CREDITOR_NOT_FOUND";
        } else if (debtor.id().equals(creditor.id())) {
            reason = "SAME_ACCOUNT";
        } else if (!debtor.currency().equals(e.currency()) || !creditor.currency().equals(e.currency())) {
            reason = "CURRENCY_MISMATCH";
        } else if (!debtor.overdraft() && debtor.balance() < e.amountMinor()) {
            reason = "INSUFFICIENT_FUNDS";
        } else if (outflowToday(debtor.id()) + e.amountMinor() > debtor.dailyLimit()) {
            reason = "DAILY_LIMIT_EXCEEDED";
        }
        record(e, reason == null ? PaymentPosted.Outcome.POSTED : PaymentPosted.Outcome.REJECTED, reason);
        if (reason == null) {
            jdbc.update("INSERT INTO postings(payment_id, account_id, direction, amount_minor) VALUES (?,?,'D',?)",
                    e.paymentId(), debtor.id(), e.amountMinor());
            jdbc.update("INSERT INTO postings(payment_id, account_id, direction, amount_minor) VALUES (?,?,'C',?)",
                    e.paymentId(), creditor.id(), e.amountMinor());
            jdbc.update("UPDATE accounts SET balance_minor = balance_minor - ? WHERE id = ?",
                    e.amountMinor(), debtor.id());
            jdbc.update("UPDATE accounts SET balance_minor = balance_minor + ? WHERE id = ?",
                    e.amountMinor(), creditor.id());
        }
        jdbc.update("UPDATE accounts SET last_seq = ? WHERE id = ?", e.debtorSeq(), debtor.id());
    }

    private void drain(String debtorId) {
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
            lockOrdered(pe.debtorAccountId(), pe.creditorAccountId());
            if (exists(pe)) {
                jdbc.update("UPDATE accounts SET last_seq = ? WHERE id = ?", pe.debtorSeq(), debtorId);
            } else {
                apply(pe);
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
        PaymentPosted posted = new PaymentPosted(PaymentPosted.SCHEMA_VERSION,
                Ids.eventId(e.paymentId(), "posted"), e.eventId(), e.paymentId(), e.clientId(),
                e.merchantId(), e.debtorAccountId(), outcome, reason, Instant.now());
        // F-12: keyed by merchant id in baseline.
        jdbc.update("INSERT INTO outbox(msg_key, payload) VALUES (?,?)",
                e.merchantId(), Json.write(posted));
        meters.counter("ledger.applied", "outcome", outcome.name()).increment();
    }

    private boolean exists(PaymentValidated e) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM ledger_payments WHERE payment_id = ?",
                Integer.class, e.paymentId());
        return n != null && n > 0;
    }

    private void lockOrdered(String a, String b) {
        jdbc.query("SELECT id FROM accounts WHERE id IN (?, ?) ORDER BY id FOR UPDATE", rs -> { }, a, b);
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

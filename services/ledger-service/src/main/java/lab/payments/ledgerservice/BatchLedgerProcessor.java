package lab.payments.ledgerservice;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lab.payments.common.TraceCarrier;
import org.springframework.beans.factory.ObjectProvider;
import java.sql.Array;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import lab.payments.common.Ids;
import lab.payments.common.Json;
import lab.payments.common.PaymentPosted;
import lab.payments.common.PaymentValidated;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F-04 (tuned): applies a whole Kafka batch in ONE transaction with JDBC batch writes instead of one
 * transaction and several round trips per record.
 *
 * The rules are the same as the record-at-a-time LedgerService (ADR-0001, ADR-0002); they are applied
 * in memory against a locked snapshot, then flushed:
 *  1. Group by debtor, apply each debtor's events in debtor_seq order (ordering is by sequence number,
 *     not by offset), so insertion order into ledger_payments follows application order.
 *  2. Lock every account the batch can touch in one statement ordered by id: debtors, creditors and
 *     the creditors of payments already parked for those debtors (drains never lock out of order).
 *     The parked set is re-read under the lock; if it grew, retry with a larger set.
 *  3. Dedup by payment_id against the database and against payments already applied in this batch. A
 *     duplicate carrying the debtor's next sequence number consumes it, exactly as in LedgerService.
 *  4. Flush in foreign-key order: ledger_payments, postings, outbox, pending inserts/deletes, then
 *     one absolute UPDATE per touched account (safe under the row lock). The CHECK constraint on
 *     balances stays as a backstop.
 */
@Component
@ConditionalOnProperty(name = "lab.tuning.f04", havingValue = "true")
class BatchLedgerProcessor {

    private static final class Acct {
        final String id;
        final String currency;
        final boolean overdraft;
        final long dailyLimit;
        long balance;
        long lastSeq;
        boolean touched;

        Acct(String id, String currency, boolean overdraft, long balance, long dailyLimit, long lastSeq) {
            this.id = id;
            this.currency = currency;
            this.overdraft = overdraft;
            this.balance = balance;
            this.dailyLimit = dailyLimit;
            this.lastSeq = lastSeq;
        }
    }

    private record LedgerRow(UUID paymentId, String debtor, long seq, String outcome, String reason) {
    }

    private record PostingRow(UUID paymentId, String account, String direction, long amount) {
    }

    private record OutboxRow(String key, String payload, String trace) {
    }

    private record PendingRow(String debtor, long seq, String payload) {
    }

    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;
    private final boolean keyByDebtor;
    private final SettlementAccounts settlement;
    private final Tracer tracer;
    private final Propagator propagator;

    BatchLedgerProcessor(JdbcTemplate jdbc, MeterRegistry meters, TransactionTemplate tx,
            @Value("${lab.tuning.f12:false}") boolean keyByDebtor, SettlementAccounts settlement,
            ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.settlement = settlement;
        this.tracer = tracer.getIfAvailable();
        this.propagator = propagator.getIfAvailable();
        this.jdbc = jdbc;
        this.meters = meters;
        this.tx = tx;
        this.keyByDebtor = keyByDebtor;
        meters.counter("ledger.parked");
    }

    /** @param shardHint the source partition of the batch: it picks the settlement shard when F-11 is on */
    void process(List<PaymentValidated> events, int shardHint, Map<UUID, String> traceparents) {
        tx.executeWithoutResult(status -> new Run(events, shardHint, traceparents).execute());
    }

    /** All mutable state of one batch. */
    private final class Run {
        private final List<PaymentValidated> events;
        private final int shardHint;
        private final Map<UUID, String> traceparents;
        private final Map<String, Acct> accts = new HashMap<>();
        private Map<String, TreeMap<Long, PaymentValidated>> pending;
        private final Set<UUID> seen = new HashSet<>();
        private final Map<String, Long> outflow = new HashMap<>();
        private final List<LedgerRow> ledgerRows = new ArrayList<>();
        private final List<PostingRow> postings = new ArrayList<>();
        private final List<OutboxRow> outbox = new ArrayList<>();
        private final List<PendingRow> pendingInserts = new ArrayList<>();
        private final List<PendingRow> pendingDeletes = new ArrayList<>();

        Run(List<PaymentValidated> events, int shardHint, Map<UUID, String> traceparents) {
            this.events = events;
            this.shardHint = shardHint;
            this.traceparents = traceparents;
        }

        void execute() {
            Map<String, List<PaymentValidated>> byDebtor = new TreeMap<>();
            for (PaymentValidated e : events) {
                byDebtor.computeIfAbsent(e.debtorAccountId(), k -> new ArrayList<>()).add(e);
            }
            byDebtor.values().forEach(list -> list.sort(Comparator.comparingLong(PaymentValidated::debtorSeq)));
            Set<String> debtors = byDebtor.keySet();

            Set<String> ids = new TreeSet<>(debtors);
            events.forEach(e -> {
                ids.add(e.creditorAccountId());
                ids.add(settlement.id(e.currency(), shardHint));
            });
            loadPending(debtors).values().forEach(m -> m.values().forEach(p -> {
                ids.add(p.creditorAccountId());
                ids.add(settlement.id(p.currency(), shardHint));
            }));
            lockAndLoad(ids);
            pending = loadPending(debtors);
            for (TreeMap<Long, PaymentValidated> parked : pending.values()) {
                for (PaymentValidated p : parked.values()) {
                    if (!ids.contains(p.creditorAccountId()) || !ids.contains(settlement.id(p.currency(), shardHint))) {
                        throw new LedgerService.LockSetChangedException();
                    }
                }
            }
            Set<UUID> candidates = new HashSet<>();
            events.forEach(e -> candidates.add(e.paymentId()));
            pending.values().forEach(m -> m.values().forEach(p -> candidates.add(p.paymentId())));
            seen.addAll(existingPayments(candidates));
            loadOutflow(debtors);

            byDebtor.values().forEach(list -> list.forEach(this::handle));
            flush();
        }

        private void handle(PaymentValidated e) {
            Acct debtor = accts.get(e.debtorAccountId());
            if (seen.contains(e.paymentId())) {
                duplicate(e, debtor);
                return;
            }
            if (SettlementAccounts.isReserved(e.debtorAccountId())) {
                // See LedgerService: a reserved debtor has no sequence to consume; a reserved creditor does.
                record(e, PaymentPosted.Outcome.REJECTED, "RESERVED_ACCOUNT");
                return;
            }
            if (debtor == null) {
                record(e, PaymentPosted.Outcome.REJECTED, "DEBTOR_NOT_FOUND");
                return;
            }
            long expected = debtor.lastSeq + 1;
            if (e.debtorSeq() > expected) {
                park(e);
            } else if (e.debtorSeq() < expected) {
                record(e, PaymentPosted.Outcome.REJECTED, "SEQUENCE_CONFLICT");
            } else {
                apply(e, debtor);
                drain(debtor);
            }
        }

        /** Same rule as LedgerService.sequenceForDuplicate. */
        private void duplicate(PaymentValidated e, Acct debtor) {
            if (debtor == null) {
                return;
            }
            long expected = debtor.lastSeq + 1;
            if (e.debtorSeq() == expected) {
                debtor.lastSeq = e.debtorSeq();
                debtor.touched = true;
                drain(debtor);
            } else if (e.debtorSeq() > expected) {
                park(e);
            }
        }

        private void park(PaymentValidated e) {
            TreeMap<Long, PaymentValidated> parked =
                    pending.computeIfAbsent(e.debtorAccountId(), k -> new TreeMap<>());
            if (parked.putIfAbsent(e.debtorSeq(), e) == null) {
                pendingInserts.add(new PendingRow(e.debtorAccountId(), e.debtorSeq(), Json.write(e)));
                meters.counter("ledger.parked").increment();
            }
        }

        private void drain(Acct debtor) {
            TreeMap<Long, PaymentValidated> parked = pending.get(debtor.id);
            while (parked != null) {
                PaymentValidated next = parked.remove(debtor.lastSeq + 1);
                if (next == null) {
                    return;
                }
                pendingDeletes.add(new PendingRow(debtor.id, next.debtorSeq(), null));
                if (seen.contains(next.paymentId())) {
                    debtor.lastSeq = next.debtorSeq();
                    debtor.touched = true;
                } else {
                    apply(next, debtor);
                }
            }
        }

        private void apply(PaymentValidated e, Acct debtor) {
            Acct creditor = accts.get(e.creditorAccountId());
            Acct settle = accts.get(settlement.id(e.currency(), shardHint));
            String reason = null;
            if (e.outcome() == PaymentValidated.Outcome.REJECTED) {
                reason = e.reasonCode();
            } else if (creditor == null) {
                reason = "CREDITOR_NOT_FOUND";
            } else if (SettlementAccounts.isReserved(creditor.id)) {
                reason = "RESERVED_ACCOUNT";
            } else if (debtor.id.equals(creditor.id)) {
                reason = "SAME_ACCOUNT";
            } else if (!debtor.currency.equals(e.currency()) || !creditor.currency.equals(e.currency())) {
                reason = "CURRENCY_MISMATCH";
            } else if (settle == null) {
                reason = "SETTLEMENT_ACCOUNT_MISSING";
            } else if (!debtor.overdraft && debtor.balance < e.amountMinor()) {
                reason = "INSUFFICIENT_FUNDS";
            } else if (outflow.getOrDefault(debtor.id, 0L) + e.amountMinor() > debtor.dailyLimit) {
                reason = "DAILY_LIMIT_EXCEEDED";
            }
            record(e, reason == null ? PaymentPosted.Outcome.POSTED : PaymentPosted.Outcome.REJECTED, reason);
            if (reason == null) {
                // Four legs through settlement (F-11); the credit is posted before the debit, and the account is
                // written back even though it nets to zero, exactly as the record path updates the row twice.
                postings.add(new PostingRow(e.paymentId(), debtor.id, "D", e.amountMinor()));
                postings.add(new PostingRow(e.paymentId(), settle.id, "C", e.amountMinor()));
                postings.add(new PostingRow(e.paymentId(), settle.id, "D", e.amountMinor()));
                postings.add(new PostingRow(e.paymentId(), creditor.id, "C", e.amountMinor()));
                debtor.balance -= e.amountMinor();
                settle.balance += e.amountMinor();
                settle.balance -= e.amountMinor();
                settle.touched = true;
                creditor.balance += e.amountMinor();
                creditor.touched = true;
                outflow.merge(debtor.id, e.amountMinor(), Long::sum);
            }
            debtor.lastSeq = e.debtorSeq();
            debtor.touched = true;
        }

        private void record(PaymentValidated e, PaymentPosted.Outcome outcome, String reason) {
            ledgerRows.add(new LedgerRow(e.paymentId(), e.debtorAccountId(), e.debtorSeq(), outcome.name(), reason));
            PaymentPosted posted = new PaymentPosted(PaymentPosted.SCHEMA_VERSION,
                    Ids.eventId(e.paymentId(), "posted"), e.eventId(), e.paymentId(), e.clientId(),
                    e.merchantId(), e.debtorAccountId(), outcome, reason, Instant.now());
            // F-12: keyed by debtor account id in tuned.
            // A batch consumer has one span for many payments, which would not continue any payment's own trace. So each
            // outcome is written under a short span that is a child of that payment's trace, taken from the record's
            // traceparent header, and the outbox publisher continues from there.
            String parent = traceparents.get(e.paymentId());
            String trace = parent != null
                    ? TraceCarrier.within(tracer, propagator, parent, "ledger.apply", () -> TraceCarrier.capture(tracer, propagator))
                    : TraceCarrier.capture(tracer, propagator);
            outbox.add(new OutboxRow(keyByDebtor ? e.debtorAccountId() : e.merchantId(), Json.write(posted), trace));
            seen.add(e.paymentId());
            meters.counter("ledger.applied", "outcome", outcome.name()).increment();
        }

        private void flush() {
            jdbc.batchUpdate("""
                    INSERT INTO ledger_payments(payment_id, debtor_account_id, debtor_seq, outcome, reason_code)
                    VALUES (?,?,?,?,?)""", ledgerRows, 500, (ps, r) -> {
                ps.setObject(1, r.paymentId());
                ps.setString(2, r.debtor());
                ps.setLong(3, r.seq());
                ps.setString(4, r.outcome());
                ps.setString(5, r.reason());
            });
            jdbc.batchUpdate("INSERT INTO postings(payment_id, account_id, direction, amount_minor) VALUES (?,?,?,?)",
                    postings, 500, (ps, r) -> {
                ps.setObject(1, r.paymentId());
                ps.setString(2, r.account());
                ps.setString(3, r.direction());
                ps.setLong(4, r.amount());
            });
            jdbc.batchUpdate("INSERT INTO outbox(msg_key, payload, trace) VALUES (?,?,?)", outbox, 500, (ps, r) -> {
                ps.setString(1, r.key());
                ps.setString(2, r.payload());
                ps.setString(3, r.trace());
            });
            jdbc.batchUpdate("""
                    INSERT INTO pending_payments(debtor_account_id, debtor_seq, payload) VALUES (?,?,?)
                    ON CONFLICT DO NOTHING""", pendingInserts, 500, (ps, r) -> {
                ps.setString(1, r.debtor());
                ps.setLong(2, r.seq());
                ps.setString(3, r.payload());
            });
            jdbc.batchUpdate("DELETE FROM pending_payments WHERE debtor_account_id = ? AND debtor_seq = ?",
                    pendingDeletes, 500, (ps, r) -> {
                ps.setString(1, r.debtor());
                ps.setLong(2, r.seq());
            });
            List<Acct> touched = accts.values().stream().filter(a -> a.touched).toList();
            jdbc.batchUpdate("UPDATE accounts SET balance_minor = ?, last_seq = ? WHERE id = ?", touched, 500,
                    (ps, a) -> {
                ps.setLong(1, a.balance);
                ps.setLong(2, a.lastSeq);
                ps.setString(3, a.id);
            });
        }

        private Map<String, TreeMap<Long, PaymentValidated>> loadPending(Set<String> debtors) {
            Map<String, TreeMap<Long, PaymentValidated>> result = new HashMap<>();
            jdbc.query(con -> {
                var ps = con.prepareStatement(
                        "SELECT debtor_account_id, debtor_seq, payload FROM pending_payments WHERE debtor_account_id = ANY(?)");
                ps.setArray(1, con.createArrayOf("text", debtors.toArray()));
                return ps;
            }, rs -> {
                result.computeIfAbsent(rs.getString(1), k -> new TreeMap<>())
                        .put(rs.getLong(2), Json.read(rs.getString(3), PaymentValidated.class));
            });
            return result;
        }

        private void lockAndLoad(Set<String> ids) {
            jdbc.query(con -> {
                var ps = con.prepareStatement("""
                        SELECT id, currency, overdraft, balance_minor, daily_limit_minor, last_seq
                        FROM accounts WHERE id = ANY(?) ORDER BY id FOR UPDATE""");
                Array array = con.createArrayOf("text", ids.toArray());
                ps.setArray(1, array);
                return ps;
            }, rs -> {
                accts.put(rs.getString(1), new Acct(rs.getString(1), rs.getString(2).trim(), rs.getBoolean(3),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6)));
            });
        }

        private Set<UUID> existingPayments(Set<UUID> ids) {
            Set<UUID> found = new HashSet<>();
            jdbc.query(con -> {
                var ps = con.prepareStatement("SELECT payment_id FROM ledger_payments WHERE payment_id = ANY(?)");
                ps.setArray(1, con.createArrayOf("uuid", ids.toArray()));
                return ps;
            }, rs -> {
                found.add(rs.getObject(1, UUID.class));
            });
            return found;
        }

        private void loadOutflow(Set<String> debtors) {
            jdbc.query(con -> {
                var ps = con.prepareStatement("""
                        SELECT account_id, COALESCE(SUM(amount_minor), 0) FROM postings
                        WHERE account_id = ANY(?) AND direction = 'D' AND created_at >= date_trunc('day', now())
                        GROUP BY account_id""");
                ps.setArray(1, con.createArrayOf("text", debtors.toArray()));
                return ps;
            }, rs -> {
                outflow.put(rs.getString(1), rs.getLong(2));
            });
        }
    }
}

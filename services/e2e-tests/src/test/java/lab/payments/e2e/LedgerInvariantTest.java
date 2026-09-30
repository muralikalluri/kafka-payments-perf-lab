package lab.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lab.payments.common.Ids;
import lab.payments.common.Json;
import lab.payments.common.PaymentValidated;
import lab.payments.common.Topics;
import org.junit.jupiter.api.Test;

class LedgerInvariantTest {

    private static final String CLIENT = "client-ledger";
    private static final String[] MERCHANTS = {"MER-1", "MER-2", "MER-3", "MER-4", "MER-5"};

    private static void submit(String debtor, String creditor, int count, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String merchant = MERCHANTS[i % MERCHANTS.length];
            calls.add(() -> Lab.post(CLIENT, "k-" + UUID.randomUUID(),
                    Lab.body(debtor, creditor, merchant, 1)).statusCode());
        }
        for (Future<Integer> f : pool.invokeAll(calls)) {
            assertThat(f.get()).isEqualTo(202);
        }
        pool.shutdown();
    }

    private static void awaitTerminal(String debtor, int expected) {
        Lab.await(expected + " terminal payments for " + debtor, () -> Lab.count(
                "SELECT count(*) FROM gateway.payments WHERE debtor_account_id = ? AND status_rank = 3",
                debtor) == expected);
    }

    private static long balance(String id) {
        return Lab.JDBC.queryForObject("SELECT balance_minor FROM ledger.accounts WHERE id = ?", Long.class, id);
    }

    /**
     * T7 + T12: 200 concurrent debits of 1 against a balance of 100, spread over five merchants (so
     * over several partitions). Exactly 100 post, the balance ends at 0 and never below, debits
     * equal credits, and payments are applied in debtor-sequence order.
     */
    @Test
    void concurrentDebitsNeverOverdrawAndApplyInDebtorOrder() throws Exception {
        String debtor = Lab.account(CLIENT, 100);
        String creditor = Lab.account(CLIENT, 0);

        submit(debtor, creditor, 200, 16);
        awaitTerminal(debtor, 200);

        assertThat(Lab.count("SELECT count(*) FROM ledger.ledger_payments WHERE debtor_account_id = ? AND outcome = 'POSTED'", debtor)).isEqualTo(100);
        assertThat(Lab.count("SELECT count(*) FROM ledger.ledger_payments WHERE debtor_account_id = ? AND reason_code = 'INSUFFICIENT_FUNDS'", debtor)).isEqualTo(100);
        assertThat(balance(debtor)).isZero();
        assertThat(balance(creditor)).isEqualTo(100);
        long debits = Lab.JDBC.queryForObject("SELECT COALESCE(SUM(amount_minor),0) FROM ledger.postings WHERE direction='D' AND account_id IN (?,?)", Long.class, debtor, creditor);
        long credits = Lab.JDBC.queryForObject("SELECT COALESCE(SUM(amount_minor),0) FROM ledger.postings WHERE direction='C' AND account_id IN (?,?)", Long.class, debtor, creditor);
        assertThat(debits).isEqualTo(credits).isEqualTo(100);

        List<Long> seqs = Lab.JDBC.queryForList("SELECT debtor_seq FROM ledger.ledger_payments WHERE debtor_account_id = ? ORDER BY id", Long.class, debtor);
        assertThat(seqs).hasSize(200);
        for (int i = 0; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).isEqualTo(i + 1L);
        }
    }

    /** T8: opposite-direction transfers between the same two accounts do not deadlock or lose money. */
    @Test
    void oppositeTransfersDoNotDeadlock() throws Exception {
        String a = Lab.account(CLIENT, 1000);
        String b = Lab.account(CLIENT, 1000);

        Thread t = new Thread(() -> {
            try {
                submit(b, a, 100, 8);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        t.start();
        submit(a, b, 100, 8);
        t.join();
        awaitTerminal(a, 100);
        awaitTerminal(b, 100);

        assertThat(Lab.count("SELECT count(*) FROM ledger.ledger_payments WHERE debtor_account_id IN (?,?) AND outcome = 'POSTED'", a, b)).isEqualTo(200);
        assertThat(balance(a)).isEqualTo(1000);
        assertThat(balance(b)).isEqualTo(1000);
    }

    /** T10: a validation rejection ends REJECTED at the gateway, with no postings. */
    @Test
    void validationRejectionProducesNoPostings() {
        String debtor = Lab.account(CLIENT, 1000);
        String key = "k-" + UUID.randomUUID();
        assertThat(Lab.post(CLIENT, key, Lab.body(debtor, "SANC-0001", "MER-1", 10)).statusCode()).isEqualTo(202);
        UUID paymentId = Ids.paymentId(CLIENT, key);

        Lab.await("rejected", () -> Lab.count(
                "SELECT count(*) FROM gateway.payments WHERE payment_id = ? AND status = 'REJECTED' AND reason_code = 'SANCTIONS_HIT'",
                paymentId) == 1);
        assertThat(Lab.count("SELECT count(*) FROM ledger.postings WHERE payment_id = ?", paymentId)).isZero();
        assertThat(balance(debtor)).isEqualTo(1000);
    }

    private static PaymentValidated validated(UUID paymentId, UUID eventId, String debtor, String creditor,
            long amount, long seq) {
        return new PaymentValidated(PaymentValidated.SCHEMA_VERSION, eventId, UUID.randomUUID(), paymentId,
                CLIENT, PaymentValidated.Outcome.VALID, null, debtor, creditor, "MER-1", amount, "USD", seq,
                Instant.now());
    }

    /** T5: redelivered validated events (same or different eventId) post exactly once. */
    @Test
    void duplicateValidatedEventsPostOnce() {
        String debtor = "T-" + UUID.randomUUID().toString().substring(0, 12);
        String creditor = "T-" + UUID.randomUUID().toString().substring(0, 12);
        Lab.ledgerAccount(debtor, CLIENT, 1000);
        Lab.ledgerAccount(creditor, CLIENT, 0);
        UUID paymentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        Lab.produce(Topics.VALIDATED, "MER-1", Json.write(validated(paymentId, eventId, debtor, creditor, 250, 1)));
        Lab.produce(Topics.VALIDATED, "MER-1", Json.write(validated(paymentId, eventId, debtor, creditor, 250, 1)));
        Lab.produce(Topics.VALIDATED, "MER-1", Json.write(validated(paymentId, UUID.randomUUID(), debtor, creditor, 250, 1)));

        Lab.await("payment applied", () -> Lab.count("SELECT count(*) FROM ledger.ledger_payments WHERE payment_id = ?", paymentId) == 1);
        // A sentinel behind the duplicates: once it is applied the duplicates have been consumed.
        UUID sentinel = UUID.randomUUID();
        Lab.produce(Topics.VALIDATED, "MER-1", Json.write(validated(sentinel, UUID.randomUUID(), debtor, creditor, 1, 2)));
        Lab.await("sentinel applied", () -> Lab.count("SELECT count(*) FROM ledger.ledger_payments WHERE payment_id = ?", sentinel) == 1);

        assertThat(Lab.count("SELECT count(*) FROM ledger.postings WHERE payment_id = ?", paymentId)).isEqualTo(Lab.postingsPerPayment());
        assertThat(balance(debtor)).isEqualTo(1000 - 250 - 1);
        assertThat(balance(creditor)).isEqualTo(250 + 1);
    }

    /** T12: events arriving out of debtor-sequence order are parked and applied in order. */
    @Test
    void outOfOrderEventsAreAppliedInSequenceOrder() {
        String debtor = "T-" + UUID.randomUUID().toString().substring(0, 12);
        String creditor = "T-" + UUID.randomUUID().toString().substring(0, 12);
        Lab.ledgerAccount(debtor, CLIENT, 1000);
        Lab.ledgerAccount(creditor, CLIENT, 0);
        UUID second = UUID.randomUUID();
        UUID first = UUID.randomUUID();

        Lab.produce(Topics.VALIDATED, "MER-2", Json.write(validated(second, UUID.randomUUID(), debtor, creditor, 20, 2)));
        Lab.await("seq 2 parked", () -> Lab.count("SELECT count(*) FROM ledger.pending_payments WHERE debtor_account_id = ?", debtor) == 1);
        assertThat(Lab.count("SELECT count(*) FROM ledger.ledger_payments WHERE payment_id = ?", second)).isZero();

        Lab.produce(Topics.VALIDATED, "MER-1", Json.write(validated(first, UUID.randomUUID(), debtor, creditor, 10, 1)));
        Lab.await("both applied", () -> Lab.count("SELECT count(*) FROM ledger.ledger_payments WHERE debtor_account_id = ?", debtor) == 2);

        List<Long> order = Lab.JDBC.queryForList("SELECT debtor_seq FROM ledger.ledger_payments WHERE debtor_account_id = ? ORDER BY id", Long.class, debtor);
        assertThat(order).containsExactly(1L, 2L);
        assertThat(Lab.count("SELECT count(*) FROM ledger.pending_payments WHERE debtor_account_id = ?", debtor)).isZero();
        assertThat(balance(debtor)).isEqualTo(970);
    }

    /**
     * Phantom-event recovery: the same payment arrives again carrying the debtor's next sequence
     * number (client retry after a failed gateway commit). It has no second effect, but it consumes
     * the number, so the debtor's next payment is not parked forever.
     */
    @Test
    void duplicateCarryingNextSequenceDoesNotStallTheDebtor() {
        String debtor = "T-" + UUID.randomUUID().toString().substring(0, 12);
        String creditor = "T-" + UUID.randomUUID().toString().substring(0, 12);
        Lab.ledgerAccount(debtor, CLIENT, 1000);
        Lab.ledgerAccount(creditor, CLIENT, 0);
        UUID p1 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();

        Lab.produce(Topics.VALIDATED, "MER-1", Json.write(validated(p1, UUID.randomUUID(), debtor, creditor, 10, 1)));
        Lab.produce(Topics.VALIDATED, "MER-1", Json.write(validated(p1, UUID.randomUUID(), debtor, creditor, 10, 2)));
        Lab.produce(Topics.VALIDATED, "MER-1", Json.write(validated(p3, UUID.randomUUID(), debtor, creditor, 5, 3)));

        Lab.await("payment behind the duplicate applied", () -> Lab.count(
                "SELECT count(*) FROM ledger.ledger_payments WHERE payment_id = ?", p3) == 1);
        assertThat(Lab.count("SELECT count(*) FROM ledger.postings WHERE payment_id = ?", p1)).isEqualTo(Lab.postingsPerPayment());
        assertThat(Lab.count("SELECT count(*) FROM ledger.pending_payments WHERE debtor_account_id = ?", debtor)).isZero();
        assertThat(balance(debtor)).isEqualTo(1000 - 10 - 5);
    }

    /**
     * Draining a parked payment whose creditor sorts BEFORE the debtor must not take a lock out of
     * order (the drain used to lock the creditor after the debtor row lock was already held).
     */
    @Test
    void drainWithCreditorSortingBeforeDebtorAppliesAndUnlocksInOrder() {
        String debtor = "T-z" + UUID.randomUUID().toString().substring(0, 10);
        String earlyCreditor = "T-a" + UUID.randomUUID().toString().substring(0, 10);
        String otherCreditor = "T-m" + UUID.randomUUID().toString().substring(0, 10);
        Lab.ledgerAccount(debtor, CLIENT, 1000);
        Lab.ledgerAccount(earlyCreditor, CLIENT, 0);
        Lab.ledgerAccount(otherCreditor, CLIENT, 0);
        UUID second = UUID.randomUUID();
        UUID first = UUID.randomUUID();

        Lab.produce(Topics.VALIDATED, "MER-2", Json.write(validated(second, UUID.randomUUID(), debtor, earlyCreditor, 20, 2)));
        Lab.await("seq 2 parked", () -> Lab.count("SELECT count(*) FROM ledger.pending_payments WHERE debtor_account_id = ?", debtor) == 1);
        Lab.produce(Topics.VALIDATED, "MER-1", Json.write(validated(first, UUID.randomUUID(), debtor, otherCreditor, 10, 1)));
        Lab.await("both applied", () -> Lab.count("SELECT count(*) FROM ledger.ledger_payments WHERE debtor_account_id = ?", debtor) == 2);

        assertThat(balance(earlyCreditor)).isEqualTo(20);
        assertThat(balance(otherCreditor)).isEqualTo(10);
        assertThat(balance(debtor)).isEqualTo(970);
    }

    /**
     * Transfers among a small ring of accounts, submitted concurrently, provoke lock contention
     * (every account is both debtor and creditor). Money is conserved, every payment reaches a
     * terminal state, and nothing is dead-lettered (a deadlock loser would be retried, not lost).
     */
    @Test
    void randomTransfersAmongFewAccountsConserveMoneyWithoutDeadLetters() throws Exception {
        long dltBefore = Lab.recordCount(Topics.VALIDATED + ".DLT") + Lab.recordCount(Topics.INITIATED + ".DLT");
        List<String> accounts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            accounts.add(Lab.account(CLIENT, 500));
        }
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Callable<Integer>> calls = new ArrayList<>();
        java.util.Random random = new java.util.Random(42);
        for (int i = 0; i < 300; i++) {
            int from = random.nextInt(accounts.size());
            int to = (from + 1 + random.nextInt(accounts.size() - 1)) % accounts.size();
            String merchant = MERCHANTS[i % MERCHANTS.length];
            String debtor = accounts.get(from);
            String creditor = accounts.get(to);
            calls.add(() -> Lab.post(CLIENT, "k-" + UUID.randomUUID(),
                    Lab.body(debtor, creditor, merchant, 1 + random.nextInt(3))).statusCode());
        }
        for (Future<Integer> f : pool.invokeAll(calls)) {
            assertThat(f.get()).isEqualTo(202);
        }
        pool.shutdown();

        Lab.await("all ring payments terminal", () -> {
            int terminal = 0;
            for (String a : accounts) {
                terminal += Lab.count("SELECT count(*) FROM gateway.payments WHERE debtor_account_id = ? AND status_rank = 3", a);
            }
            return terminal == 300;
        });
        long total = 0;
        for (String a : accounts) {
            assertThat(balance(a)).isGreaterThanOrEqualTo(0);
            total += balance(a);
        }
        assertThat(total).isEqualTo(6 * 500L);
        assertThat(Lab.recordCount(Topics.VALIDATED + ".DLT") + Lab.recordCount(Topics.INITIATED + ".DLT"))
                .isEqualTo(dltBefore);
    }

    /**
     * An event naming the same account as debtor and creditor (validation would stop it, but events can be produced
     * directly) is rejected and consumes its sequence number; it must not poison the consumer.
     */
    @Test
    void sameAccountEventIsRejectedWithoutBlockingTheDebtor() {
        String account = "T-" + UUID.randomUUID().toString().substring(0, 12);
        String other = "T-" + UUID.randomUUID().toString().substring(0, 12);
        Lab.ledgerAccount(account, CLIENT, 1000);
        Lab.ledgerAccount(other, CLIENT, 0);
        UUID same = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        Lab.produce(Topics.VALIDATED, "MER-1", Json.write(validated(same, UUID.randomUUID(), account, account, 10, 1)));
        Lab.produce(Topics.VALIDATED, "MER-1", Json.write(validated(next, UUID.randomUUID(), account, other, 10, 2)));
        Lab.await("rejected and the next payment applied", () -> Lab.count(
                "SELECT count(*) FROM ledger.ledger_payments WHERE (payment_id = ? AND reason_code = 'SAME_ACCOUNT') "
                        + "OR (payment_id = ? AND outcome = 'POSTED')", same, next) == 2);
        assertThat(balance(account)).isEqualTo(990);
    }
}

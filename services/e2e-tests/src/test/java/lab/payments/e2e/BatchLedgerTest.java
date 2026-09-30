package lab.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lab.payments.common.Json;
import lab.payments.common.PaymentValidated;
import lab.payments.common.Topics;
import org.junit.jupiter.api.Test;

/**
 * F-04: the ledger consumers are paused so records queue up and arrive together (one batch in tuned,
 * one at a time in baseline). The ledger invariants must hold either way.
 */
class BatchLedgerTest {

    private static final String CLIENT = "client-batch";

    private static PaymentValidated event(UUID paymentId, String debtor, String creditor, long amount, long seq) {
        return new PaymentValidated(PaymentValidated.SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(), paymentId,
                CLIENT, PaymentValidated.Outcome.VALID, null, debtor, creditor, "MER-1", amount, "USD", seq,
                Instant.now());
    }

    private static void send(PaymentValidated e) {
        Lab.produce(Topics.VALIDATED, e.merchantId(), Json.write(e));
    }

    private static long balance(String id) {
        return Lab.JDBC.queryForObject("SELECT balance_minor FROM ledger.accounts WHERE id = ?", Long.class, id);
    }

    private static String ledgerOnly(long balance) {
        String id = "T-" + UUID.randomUUID().toString().substring(0, 12);
        Lab.ledgerAccount(id, CLIENT, balance);
        return id;
    }

    /**
     * Duplicates (same id and seq), a retry of a payment carrying the debtor's next sequence, sequences
     * out of order, and opposite-direction transfers, all queued together.
     */
    @Test
    void duplicatesReorderingAndOppositeTransfersInOneBatch() {
        String a = ledgerOnly(1000);
        String b = ledgerOnly(1000);
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        UUID q1 = UUID.randomUUID();

        Lab.pauseLedgerConsumers();
        try {
            send(event(p3, a, b, 5, 4));
            send(event(q1, b, a, 7, 1));
            send(event(p2, a, b, 20, 2));
            send(event(p1, a, b, 10, 3));   // retry of P1 arriving with the next sequence number
            send(event(p1, a, b, 10, 1));
            send(event(p1, a, b, 10, 1));   // plain duplicate
        } finally {
            Lab.resumeLedgerConsumers();
        }

        Lab.await("A and B applied", () -> Lab.count(
                "SELECT count(*) FROM ledger.ledger_payments WHERE debtor_account_id IN (?, ?)", a, b) == 4);
        List<Long> order = Lab.JDBC.queryForList(
                "SELECT debtor_seq FROM ledger.ledger_payments WHERE debtor_account_id = ? ORDER BY id", Long.class, a);
        assertThat(order).containsExactly(1L, 2L, 4L); // seq 3 was consumed by the retry, no row
        assertThat(Lab.count("SELECT count(*) FROM ledger.postings WHERE payment_id = ?", p1)).isEqualTo(Lab.postingsPerPayment());
        assertThat(Lab.count("SELECT count(*) FROM ledger.pending_payments WHERE debtor_account_id IN (?, ?)", a, b)).isZero();
        assertThat(balance(a)).isEqualTo(1000 - 10 - 20 - 5 + 7);
        assertThat(balance(b)).isEqualTo(1000 + 10 + 20 + 5 - 7);
    }

    /** A poison record in the middle of a batch goes to the DLT alone; its neighbours are applied. */
    @Test
    void poisonRecordIsDeadLetteredAloneAndTheRestIsApplied() {
        String debtor = ledgerOnly(1000);
        String creditor = ledgerOnly(0);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        long dltBefore = Lab.recordCount(Topics.VALIDATED + ".DLT");

        Lab.pauseLedgerConsumers();
        try {
            send(event(first, debtor, creditor, 10, 1));
            Lab.produce(Topics.VALIDATED, "MER-1", "{ this is not json");
            send(event(second, debtor, creditor, 20, 2));
        } finally {
            Lab.resumeLedgerConsumers();
        }

        Lab.await("valid neighbours applied", () -> Lab.count(
                "SELECT count(*) FROM ledger.ledger_payments WHERE debtor_account_id = ?", debtor) == 2);
        Lab.await("poison record dead-lettered", () -> Lab.recordCount(Topics.VALIDATED + ".DLT") == dltBefore + 1);
        assertThat(balance(debtor)).isEqualTo(970);
        assertThat(balance(creditor)).isEqualTo(30);
    }

    /** A database that stops answering for a while must not dead-letter anything; work resumes after. */
    @Test
    void databaseOutageDoesNotDeadLetterAndWorkResumes() throws Exception {
        String debtor = ledgerOnly(1000);
        String creditor = ledgerOnly(0);
        UUID paymentId = UUID.randomUUID();
        long dltBefore = Lab.recordCount(Topics.VALIDATED + ".DLT");

        Lab.pausePostgres();
        try {
            send(event(paymentId, debtor, creditor, 10, 1));
            Thread.sleep(4_000);
        } finally {
            Lab.unpausePostgres();
        }

        Lab.await("applied after the database came back", () -> Lab.count(
                "SELECT count(*) FROM ledger.ledger_payments WHERE payment_id = ?", paymentId) == 1);
        assertThat(Lab.recordCount(Topics.VALIDATED + ".DLT")).isEqualTo(dltBefore);
        assertThat(balance(debtor)).isEqualTo(990);
    }
}

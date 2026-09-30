package lab.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.http.HttpResponse;
import java.util.UUID;
import lab.payments.common.Ids;
import lab.payments.common.Topics;
import org.junit.jupiter.api.Test;

/** T3 and F-07: what a POST does while Kafka is down, and how a re-sent outbox event is absorbed. */
class OutboxTest {

    private static final String CLIENT = "client-outbox";

    /**
     * Baseline sends inside the transaction, so a dead broker means 503 and nothing persisted; a retry
     * after recovery yields exactly one payment. Tuned accepts (202) and publishes after recovery.
     */
    @Test
    void postWhileKafkaIsDown() {
        String debtor = Lab.account(CLIENT, 1000);
        String creditor = Lab.account(CLIENT, 0);
        String key = "k-" + UUID.randomUUID();
        String body = Lab.body(debtor, creditor, "MER-1", 10);

        HttpResponse<String> during;
        Lab.pauseKafka();
        try {
            during = Lab.post(CLIENT, key, body);
        } finally {
            Lab.unpauseKafka();
        }
        if (Lab.tuned()) {
            assertThat(during.statusCode()).isEqualTo(202);
        } else {
            assertThat(during.statusCode()).isEqualTo(503);
            assertThat(Lab.count("SELECT count(*) FROM gateway.payments WHERE idempotency_key = ?", key)).isZero();
        }

        HttpResponse<String> retry = Lab.post(CLIENT, key, body);
        assertThat(retry.statusCode()).isEqualTo(202);
        UUID paymentId = Ids.paymentId(CLIENT, key);
        assertThat(retry.body()).contains(paymentId.toString());
        assertThat(Lab.count("SELECT count(*) FROM gateway.payments WHERE idempotency_key = ?", key)).isEqualTo(1);
        Lab.await("terminal after recovery", () -> Lab.count(
                "SELECT count(*) FROM gateway.payments WHERE payment_id = ? AND status_rank = 3", paymentId) == 1);
        assertThat(Lab.count("SELECT count(*) FROM ledger.postings WHERE payment_id = ?", paymentId)).isEqualTo(2);
        if (Lab.tuned()) {
            // No phantom events without a broker call inside the transaction.
            assertThat(Lab.count("SELECT count(*) FROM ledger.ledger_payments WHERE reason_code = 'SEQUENCE_CONFLICT'")).isZero();
        }
    }

    /** A crash between send and delete re-sends an outbox event; the ledger must apply it once. */
    @Test
    void republishedOutboxEventIsAppliedOnce() {
        assumeTrue(Lab.tuned(), "the gateway outbox only exists in the tuned profile");
        String debtor = Lab.account(CLIENT, 1000);
        String creditor = Lab.account(CLIENT, 0);
        String key = "k-" + UUID.randomUUID();
        assertThat(Lab.post(CLIENT, key, Lab.body(debtor, creditor, "MER-1", 10)).statusCode()).isEqualTo(202);
        UUID paymentId = Ids.paymentId(CLIENT, key);
        Lab.await("terminal", () -> Lab.count(
                "SELECT count(*) FROM gateway.payments WHERE payment_id = ? AND status_rank = 3", paymentId) == 1);

        String payload = Lab.valueOf(Topics.INITIATED, paymentId.toString());
        Lab.JDBC.update("INSERT INTO gateway.outbox(msg_key, payload) VALUES (?, ?)", debtor, payload);
        Lab.await("outbox drained", () -> Lab.count("SELECT count(*) FROM gateway.outbox") == 0);

        // A later payment from the same debtor only completes after the duplicate has been consumed.
        String key2 = "k-" + UUID.randomUUID();
        assertThat(Lab.post(CLIENT, key2, Lab.body(debtor, creditor, "MER-1", 7)).statusCode()).isEqualTo(202);
        UUID second = Ids.paymentId(CLIENT, key2);
        Lab.await("second terminal", () -> Lab.count(
                "SELECT count(*) FROM gateway.payments WHERE payment_id = ? AND status_rank = 3", second) == 1);

        assertThat(Lab.count("SELECT count(*) FROM ledger.ledger_payments WHERE payment_id = ?", paymentId)).isEqualTo(1);
        assertThat(Lab.count("SELECT count(*) FROM ledger.postings WHERE payment_id = ?", paymentId)).isEqualTo(2);
        long balance = Lab.JDBC.queryForObject("SELECT balance_minor FROM ledger.accounts WHERE id = ?", Long.class, debtor);
        assertThat(balance).isEqualTo(1000 - 10 - 7);
    }
}

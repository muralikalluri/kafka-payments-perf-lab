package lab.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import lab.payments.common.Ids;
import lab.payments.common.Json;
import lab.payments.common.PaymentInitiated;
import lab.payments.common.Topics;
import org.junit.jupiter.api.Test;

/**
 * Idempotence (F-02) only suppresses producer retries within one session; consumers must still absorb duplicates
 * from every topic, in both profiles (ADR-0001). Each test injects the same record twice.
 */
class DuplicateDeliveryTest {

    private static final String CLIENT = "client-dup";

    private static PaymentInitiated initiated(UUID paymentId, String key, String debtor, String creditor, long amount, long seq) {
        return new PaymentInitiated(PaymentInitiated.SCHEMA_VERSION, Ids.eventId(paymentId, "initiated"), paymentId,
                CLIENT, key, debtor, creditor, "MER-1", amount, "USD", seq, Instant.now());
    }

    /** Same initiated record twice: validation emits it twice, the ledger applies the payment once. */
    @Test
    void duplicateInitiatedRecordIsAppliedOnce() {
        String debtor = Lab.account(CLIENT, 1000);
        String creditor = Lab.account(CLIENT, 0);
        String key = "dup-" + UUID.randomUUID();
        UUID paymentId = Ids.paymentId(CLIENT, key);
        String json = Json.write(initiated(paymentId, key, debtor, creditor, 40, 1));
        Lab.produce(Topics.INITIATED, debtor, json);
        Lab.produce(Topics.INITIATED, debtor, json);

        Lab.await("applied", () -> Lab.count("SELECT count(*) FROM ledger.ledger_payments WHERE payment_id = ?", paymentId) == 1);
        // A later payment for the same debtor is applied only after the duplicate was consumed.
        UUID second = Ids.paymentId(CLIENT, key + "-2");
        Lab.produce(Topics.INITIATED, debtor, Json.write(initiated(second, key + "-2", debtor, creditor, 5, 2)));
        Lab.await("second applied", () -> Lab.count("SELECT count(*) FROM ledger.ledger_payments WHERE payment_id = ?", second) == 1);

        assertThat(Lab.count("SELECT count(*) FROM ledger.ledger_payments WHERE payment_id = ?", paymentId)).isEqualTo(1);
        assertThat(Lab.count("SELECT count(*) FROM ledger.postings WHERE payment_id = ?", paymentId)).isEqualTo(Lab.postingsPerPayment());
        long balance = Lab.JDBC.queryForObject("SELECT balance_minor FROM ledger.accounts WHERE id = ?", Long.class, debtor);
        assertThat(balance).isEqualTo(1000 - 40 - 5);
    }

    /** Same posted record twice: the gateway's status update is monotonic and applied once. */
    @Test
    void duplicatePostedRecordUpdatesStatusOnce() throws Exception {
        String debtor = Lab.account(CLIENT, 1000);
        String creditor = Lab.account(CLIENT, 0);
        String key = "dup-" + UUID.randomUUID();
        assertThat(Lab.post(CLIENT, key, Lab.body(debtor, creditor, "MER-1", 10)).statusCode()).isEqualTo(202);
        UUID paymentId = Ids.paymentId(CLIENT, key);
        Lab.await("terminal", () -> Lab.count(
                "SELECT count(*) FROM gateway.payments WHERE payment_id = ? AND status_rank = 3", paymentId) == 1);

        String posted = Lab.valueOf(Topics.POSTED, paymentId.toString());
        String updatedAt = Lab.JDBC.queryForObject("SELECT updated_at::text FROM gateway.payments WHERE payment_id = ?", String.class, paymentId);
        double before = terminalCount();
        Lab.produce(Topics.POSTED, debtor, posted);
        Lab.produce(Topics.POSTED, debtor, posted);
        Thread.sleep(3000); // the gateway consumer is far quicker than this

        assertThat(terminalCount()).isEqualTo(before);
        assertThat(Lab.JDBC.queryForObject("SELECT updated_at::text FROM gateway.payments WHERE payment_id = ?", String.class, paymentId))
                .isEqualTo(updatedAt);
    }

    private static double terminalCount() {
        return Lab.GATEWAY.getBean(MeterRegistry.class).find("payments.terminal").counters().stream()
                .mapToDouble(c -> c.count()).sum();
    }
}

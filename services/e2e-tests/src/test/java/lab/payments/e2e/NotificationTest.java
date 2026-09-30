package lab.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lab.payments.common.Ids;
import lab.payments.common.Json;
import lab.payments.common.PaymentPosted;
import lab.payments.common.Topics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * F-05: baseline calls the webhook from the gateway's status listener (blocking); tuned hands off to
 * notification-service. In both, a slow or failing webhook must never lose or double a status update.
 */
class NotificationTest {

    private static final String CLIENT = "client-notify";

    @AfterEach
    void healthyWebhook() {
        Lab.resetSimulator();
    }

    private static List<UUID> pay(int n, String debtor, String creditor) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String key = "n-" + UUID.randomUUID();
            assertThat(Lab.post(CLIENT, key, Lab.body(debtor, creditor, "MER-1", 1)).statusCode()).isEqualTo(202);
            ids.add(Ids.paymentId(CLIENT, key));
        }
        return ids;
    }

    private static int terminal(List<UUID> ids) {
        return countIn("SELECT count(*) FROM gateway.payments WHERE status_rank = 3 AND payment_id", ids);
    }

    private static int receipts(List<UUID> ids) {
        return countIn("SELECT count(*) FROM notification.receipts WHERE payment_id", ids);
    }

    private static int countIn(String prefix, List<UUID> ids) {
        String placeholders = String.join(",", ids.stream().map(i -> "?::uuid").toList());
        Integer n = Lab.JDBC.queryForObject(prefix + " IN (" + placeholders + ")", Integer.class,
                ids.stream().map(UUID::toString).toArray());
        return n == null ? 0 : n;
    }

    private static double gatewayCounter(String name) {
        return Lab.GATEWAY.getBean(MeterRegistry.class).find(name).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    /** With a healthy webhook every posted payment is delivered to its own client's endpoint, once in effect. */
    @Test
    void everyPostedPaymentIsNotifiedOnceInEffect() {
        String debtor = Lab.account(CLIENT, 10_000);
        String creditor = Lab.account(CLIENT, 0);
        List<UUID> ids = pay(20, debtor, creditor);
        Lab.await("terminal", () -> terminal(ids) == ids.size());
        Lab.await("receipts", () -> receipts(ids) == ids.size());
        assertThat(Lab.JDBC.queryForObject(
                "SELECT count(*) FROM notification.receipts WHERE payment_id = ANY(?::uuid[]) AND client_id <> ?",
                Integer.class, "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}", CLIENT))
                .as("receipts only for the payment's own client").isZero();
        assertThat(Lab.JDBC.queryForObject(
                "SELECT COALESCE(max(deliveries), 0) FROM notification.receipts WHERE payment_id = ANY(?::uuid[])",
                Integer.class, "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}"))
                .as("delivered once each").isEqualTo(1);
    }

    /** A webhook that always fails: statuses are still updated exactly once. */
    @Test
    void failingWebhookNeverLosesOrDoublesStatusUpdates() {
        String debtor = Lab.account(CLIENT, 10_000);
        String creditor = Lab.account(CLIENT, 0);
        double failedBefore = gatewayCounter("notifications.failed");
        Lab.simulate(0L, 1.0, 0.0);
        List<UUID> ids = pay(15, debtor, creditor);
        Lab.await("all terminal despite the failing webhook", () -> terminal(ids) == ids.size());
        assertThat(receipts(ids)).as("the webhook rejected every call before recording").isZero();
        if (Lab.tuned()) {
            Lab.await("every notification ends dead after its attempts", () -> countIn(
                    "SELECT count(*) FROM notification.notifications WHERE state = 'DEAD' AND payment_id", ids) == ids.size());
        } else {
            Lab.await("failures counted", () -> gatewayCounter("notifications.failed") - failedBefore >= ids.size());
        }
    }

    /** Tuned: a webhook that records the receipt and then answers 500 causes retries the receiver deduplicates. */
    @Test
    void failAfterAcceptIsRetriedAndDeduplicatedByTheReceiver() {
        assumeTrue(Lab.tuned(), "retries exist only in the tuned dispatcher");
        String debtor = Lab.account(CLIENT, 10_000);
        String creditor = Lab.account(CLIENT, 0);
        Lab.simulate(0L, 0.0, 1.0);
        List<UUID> ids = pay(10, debtor, creditor);
        Lab.await("dead after all attempts", () -> countIn(
                "SELECT count(*) FROM notification.notifications WHERE state = 'DEAD' AND payment_id", ids) == ids.size());
        assertThat(receipts(ids)).as("one receipt row per payment").isEqualTo(ids.size());
        assertThat(Lab.JDBC.queryForObject("SELECT min(deliveries) FROM notification.receipts WHERE payment_id = ANY(?::uuid[])",
                Integer.class, "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}"))
                .as("each was delivered more than once").isGreaterThan(1);
    }

    /** Tuned: the same posted event twice yields one notification row. */
    @Test
    void duplicatePostedEventCreatesOneNotification() throws Exception {
        assumeTrue(Lab.tuned(), "the notification table exists only for the tuned dispatcher");
        String debtor = Lab.account(CLIENT, 10_000);
        String creditor = Lab.account(CLIENT, 0);
        UUID paymentId = UUID.randomUUID();
        PaymentPosted event = new PaymentPosted(PaymentPosted.SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(), paymentId,
                CLIENT, "MER-1", debtor, PaymentPosted.Outcome.POSTED, null, Instant.now());
        Lab.produce(Topics.POSTED, debtor, Json.write(event));
        Lab.produce(Topics.POSTED, debtor, Json.write(event));
        Lab.await("stored", () -> Lab.count("SELECT count(*) FROM notification.notifications WHERE payment_id = ?", paymentId) >= 1);
        Thread.sleep(1500);
        assertThat(Lab.count("SELECT count(*) FROM notification.notifications WHERE payment_id = ?", paymentId)).isEqualTo(1);
    }

    /**
     * The head-of-line effect: a slow webhook delays status updates when the gateway makes the call itself (one consumer
     * thread), and does not when the notification service does.
     */
    @Test
    void slowWebhookDelaysStatusUpdatesOnlyWhenTheGatewayCallsIt() {
        String debtor = Lab.account(CLIENT, 10_000);
        String creditor = Lab.account(CLIENT, 0);
        int payments = 8;
        long latencyMs = 500;
        Lab.simulate(latencyMs, 0.0, 0.0);
        long started = System.currentTimeMillis();
        List<UUID> ids = pay(payments, debtor, creditor);
        Lab.await("terminal", () -> terminal(ids) == ids.size());
        long elapsed = System.currentTimeMillis() - started;
        if (Lab.tuned()) {
            assertThat(elapsed).as("status updates do not wait for the webhook").isLessThan(payments * latencyMs * 3 / 4);
        } else {
            assertThat(elapsed).as("one blocking call per status update on a single thread")
                    .isGreaterThanOrEqualTo(payments * latencyMs * 3 / 4);
        }
    }
}

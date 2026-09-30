package lab.payments.notificationservice;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lab.payments.common.PaymentPosted;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The receiving end of a client's webhook, simulated. It stands in for a client system: it answers after a
 * configurable delay, can fail before accepting a notification (nothing recorded, 503) or after recording it (500, the
 * ambiguous case that makes a sender retry), and deduplicates by payment id.
 *
 * Deliberately insecure for demonstration. Do not deploy. (No authentication; any caller can post a receipt.)
 */
@RestController
class WebhookController {

    private final JdbcTemplate jdbc;
    private final SimulatorConfig config;
    private final MeterRegistry meters;

    WebhookController(JdbcTemplate jdbc, SimulatorConfig config, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.config = config;
        this.meters = meters;
    }

    @PostMapping("/webhooks/{clientId}")
    ResponseEntity<Map<String, String>> receive(@PathVariable String clientId, @RequestBody PaymentPosted body)
            throws InterruptedException {
        if (config.latencyMs() > 0) {
            Thread.sleep(config.latencyMs());
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextDouble() < config.failureRate()) {
            meters.counter("webhook.rejected").increment();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "SIMULATED_FAILURE"));
        }
        // A receipt must never reach another client's endpoint.
        if (!clientId.equals(body.clientId())) {
            meters.counter("webhook.client_mismatch").increment();
            return ResponseEntity.badRequest().body(Map.of("error", "CLIENT_MISMATCH"));
        }
        List<Integer> deliveries = jdbc.queryForList("""
                INSERT INTO receipts(payment_id, client_id) VALUES (?, ?)
                ON CONFLICT (payment_id) DO UPDATE SET deliveries = receipts.deliveries + 1
                RETURNING deliveries""", Integer.class, body.paymentId(), clientId);
        meters.counter("webhook.received").increment();
        if (deliveries.get(0) > 1) {
            meters.counter("webhook.duplicates").increment();
        }
        if (random.nextDouble() < config.failAfterAcceptRate()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "FAILED_AFTER_ACCEPT"));
        }
        return ResponseEntity.ok(Map.of("status", "received"));
    }
}

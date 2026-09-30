package lab.payments.notificationservice;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * F-05 (tuned): delivers stored notifications with bounded concurrency, an Idempotency-Key header (the payment id),
 * exponential backoff and a DEAD state after the last attempt. Delivery is at-least-once; the receiver deduplicates,
 * so the effect is "once" without pretending HTTP can be exactly-once. Rows are claimed with a short lease
 * (FOR UPDATE SKIP LOCKED plus a pushed-out next_attempt_at), so no database transaction is open during an HTTP call.
 */
@Component
@ConditionalOnProperty(name = "lab.tuning.f05", havingValue = "true")
class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
    private static final int BATCH = 200;

    private record Claimed(UUID paymentId, String clientId, String payload, int attempts, Instant postedAt) {
    }

    private final JdbcTemplate jdbc;
    private final WebhookTarget target;
    private final MeterRegistry meters;
    private final int maxAttempts;
    private final long backoffBaseMs;
    private final Semaphore inFlight;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Timer latency;

    DeliveryWorker(JdbcTemplate jdbc, WebhookTarget target, MeterRegistry meters,
            @Value("${lab.notification.max-attempts:5}") int maxAttempts,
            @Value("${lab.notification.backoff-base-ms:500}") long backoffBaseMs,
            @Value("${lab.notification.concurrency:32}") int concurrency) {
        this.jdbc = jdbc;
        this.target = target;
        this.meters = meters;
        this.maxAttempts = maxAttempts;
        this.backoffBaseMs = backoffBaseMs;
        this.inFlight = new Semaphore(concurrency);
        this.latency = Timer.builder("notifications.delivery.latency")
                .description("From the payment's posted time to the webhook accepting it")
                .publishPercentileHistogram().register(meters);
        Gauge.builder("notifications.backlog", () -> count("PENDING")).register(meters);
        Gauge.builder("notifications.dead", () -> count("DEAD")).register(meters);
    }

    private double count(String state) {
        try {
            Long n = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE state = ?", Long.class, state);
            return n == null ? 0 : n;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    @Scheduled(fixedDelayString = "${lab.notification.poll-ms:50}")
    void tick() {
        int room = inFlight.availablePermits();
        if (room <= 0) {
            return;
        }
        for (Claimed row : claim(Math.min(room, BATCH))) {
            inFlight.acquireUninterruptibly();
            executor.submit(() -> {
                try {
                    deliver(row);
                } catch (RuntimeException e) {
                    log.warn("delivery of {} failed unexpectedly: {}", row.paymentId(), e.toString());
                } finally {
                    inFlight.release();
                }
            });
        }
    }

    private List<Claimed> claim(int limit) {
        return jdbc.query("""
                UPDATE notifications SET next_attempt_at = now() + interval '30 seconds'
                WHERE payment_id IN (
                    SELECT payment_id FROM notifications
                    WHERE state = 'PENDING' AND next_attempt_at <= now()
                    ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING payment_id, client_id, payload, attempts, posted_at""",
                (rs, i) -> new Claimed(rs.getObject("payment_id", UUID.class), rs.getString("client_id"),
                        rs.getString("payload"), rs.getInt("attempts"), rs.getTimestamp("posted_at").toInstant()),
                limit);
    }

    private void deliver(Claimed row) {
        boolean ok = false;
        try {
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(target.urlFor(row.clientId())))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", row.paymentId().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(row.payload())).build(),
                    HttpResponse.BodyHandlers.discarding());
            ok = response.statusCode() / 100 == 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("delivery attempt failed for {}: {}", row.paymentId(), e.toString());
        }
        if (ok) {
            jdbc.update("UPDATE notifications SET state = 'DELIVERED', attempts = attempts + 1, delivered_at = now() "
                    + "WHERE payment_id = ? AND state = 'PENDING'", row.paymentId());
            latency.record(Duration.between(row.postedAt(), Instant.now()));
            meters.counter("notifications.delivered").increment();
            return;
        }
        meters.counter("notifications.failed").increment();
        int attempts = row.attempts() + 1;
        if (attempts >= maxAttempts) {
            jdbc.update("UPDATE notifications SET state = 'DEAD', attempts = ? WHERE payment_id = ? AND state = 'PENDING'", attempts, row.paymentId());
            meters.counter("notifications.dead_total").increment();
        } else {
            long backoff = backoffBaseMs * (1L << Math.min(attempts - 1, 10));
            jdbc.update("UPDATE notifications SET attempts = ?, next_attempt_at = now() + (? * interval '1 millisecond') "
                    + "WHERE payment_id = ? AND state = 'PENDING'", attempts, backoff, row.paymentId());
        }
    }
}

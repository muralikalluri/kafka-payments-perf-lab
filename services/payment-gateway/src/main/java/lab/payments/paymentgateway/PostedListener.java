package lab.payments.paymentgateway;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lab.payments.common.EventLog;
import lab.payments.common.Json;
import lab.payments.common.PaymentPosted;
import lab.payments.common.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Maintains the status read model. Idempotent and monotonic: status never moves backwards. */
@Component
class PostedListener {

    private static final Logger log = LoggerFactory.getLogger(PostedListener.class);
    private static final int TERMINAL_RANK = 3;

    private final JdbcTemplate jdbc;
    private final NotificationStub notifications;

    private final MeterRegistry meters;
    private final Timer endToEnd;

    PostedListener(JdbcTemplate jdbc, NotificationStub notifications, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.meters = meters;
        // POST accepted -> terminal status recorded at the gateway (the SLO's end-to-end latency).
        this.endToEnd = Timer.builder("payments.e2e.latency")
                .description("Time from payment acceptance to its terminal status")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(60))
                .register(meters);
    }

    @KafkaListener(topics = Topics.POSTED)
    void onPosted(String payload) {
        PaymentPosted event = Json.read(payload, PaymentPosted.class);
        EventLog.event(log, "payment posted", event.paymentId().toString(), () -> payload); // F-09
        List<Instant> created = jdbc.query("""
                UPDATE payments SET status = ?, status_rank = ?, reason_code = ?, updated_at = now()
                WHERE payment_id = ? AND status_rank < ?
                RETURNING created_at""",
                (rs, i) -> rs.getTimestamp(1).toInstant(),
                event.outcome().name(), TERMINAL_RANK, event.reasonCode(), event.paymentId(),
                TERMINAL_RANK);
        if (!created.isEmpty()) {
            endToEnd.record(Duration.between(created.get(0), Instant.now()));
            meters.counter("payments.terminal", "outcome", event.outcome().name()).increment();
            notifications.notifyOutcome(event);
        }
    }
}

package lab.payments.paymentgateway;

import lab.payments.common.Json;
import lab.payments.common.PaymentPosted;
import lab.payments.common.Topics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Maintains the status read model. Idempotent and monotonic: status never moves backwards. */
@Component
class PostedListener {

    private static final int TERMINAL_RANK = 3;

    private final JdbcTemplate jdbc;
    private final NotificationStub notifications;

    PostedListener(JdbcTemplate jdbc, NotificationStub notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    @KafkaListener(topics = Topics.POSTED)
    void onPosted(String payload) {
        PaymentPosted event = Json.read(payload, PaymentPosted.class);
        int updated = jdbc.update("""
                UPDATE payments SET status = ?, status_rank = ?, reason_code = ?, updated_at = now()
                WHERE payment_id = ? AND status_rank < ?""",
                event.outcome().name(), TERMINAL_RANK, event.reasonCode(), event.paymentId(),
                TERMINAL_RANK);
        if (updated > 0) {
            notifications.notifyOutcome(event);
        }
    }
}

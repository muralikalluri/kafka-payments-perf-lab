package lab.payments.notificationservice;

import lab.payments.common.Json;
import lab.payments.common.PaymentPosted;
import lab.payments.common.Topics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.ExponentialBackOff;
import java.sql.Timestamp;

/**
 * F-05 (tuned): the notification service reads payments.posted in its own consumer group, so the gateway makes no
 * webhook call at all. The primary key deduplicates duplicate posted events; the offset is committed once the row is
 * stored, and delivery happens later from the table (DeliveryWorker). A slow webhook therefore becomes table growth,
 * never consumer lag on the status-update path.
 */
@Component
@ConditionalOnProperty(name = "lab.tuning.f05", havingValue = "true")
class PostedEventConsumer {

    private final JdbcTemplate jdbc;

    PostedEventConsumer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @KafkaListener(topics = Topics.POSTED)
    void onPosted(String payload) {
        PaymentPosted event = Json.read(payload, PaymentPosted.class);
        jdbc.update("""
                INSERT INTO notifications(payment_id, client_id, outcome, state, posted_at, payload)
                VALUES (?, ?, ?, 'PENDING', ?, ?)
                ON CONFLICT DO NOTHING""",
                event.paymentId(), event.clientId(), event.outcome().name(),
                Timestamp.from(event.postedAt()), payload);
    }

    @Configuration
    @ConditionalOnProperty(name = "lab.tuning.f05", havingValue = "true")
    static class ErrorHandling {

        /** Retry a database blip for a while; after that the framework logs and skips the record. */
        @Bean
        DefaultErrorHandler errorHandler() {
            ExponentialBackOff backOff = new ExponentialBackOff(200, 2.0);
            backOff.setMaxElapsedTime(30_000);
            return new DefaultErrorHandler(backOff);
        }
    }
}

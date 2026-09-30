package lab.payments.paymentgateway;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lab.payments.common.Topics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * F-07 (tuned): publishes committed outbox rows. At-least-once: a crash between send and delete
 * re-sends the same event (same eventId), which the ledger absorbs by paymentId. A single publisher
 * per instance; several instances would need a lease (SKIP LOCKED held across the send would bring
 * back the long-transaction anti-pattern).
 */
@Component
@ConditionalOnProperty(name = "lab.tuning.f07", havingValue = "true")
class GatewayOutboxPublisher {

    private record Row(long id, String key, String payload) {
    }

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;

    GatewayOutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        Gauge.builder("gateway.outbox.backlog", () -> {
            try {
                Long n = jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class);
                return n == null ? 0 : n;
            } catch (RuntimeException e) {
                return Double.NaN;
            }
        }).register(meters);
    }

    @Scheduled(fixedDelayString = "${lab.outbox.poll-ms:50}")
    synchronized void flush() {
        List<Row> rows = jdbc.query("SELECT id, msg_key, payload FROM outbox ORDER BY id LIMIT 500",
                (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3)));
        List<Long> sent = new ArrayList<>();
        try {
            for (Row row : rows) {
                kafka.send(Topics.INITIATED, row.key(), row.payload()).get(10, TimeUnit.SECONDS);
                sent.add(row.id());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // broker trouble: keep the unsent rows and retry on the next tick
        } finally {
            deleteSent(sent);
        }
    }

    private void deleteSent(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update(con -> {
            var ps = con.prepareStatement("DELETE FROM outbox WHERE id = ANY(?)");
            ps.setArray(1, con.createArrayOf("bigint", ids.toArray()));
            return ps;
        });
    }
}

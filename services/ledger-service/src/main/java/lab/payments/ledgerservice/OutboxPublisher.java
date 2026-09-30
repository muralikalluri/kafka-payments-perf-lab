package lab.payments.ledgerservice;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lab.payments.common.Topics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Publishes committed outcomes. At-least-once; the gateway's status update is idempotent. */
@Component
class OutboxPublisher {

    private record Row(long id, String key, String payload) {
    }

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;

    OutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        Gauge.builder("ledger.outbox.backlog", () -> count("outbox")).register(meters);
        Gauge.builder("ledger.pending.payments", () -> count("pending_payments")).register(meters);
    }

    private double count(String table) {
        try {
            Long n = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
            return n == null ? 0 : n;
        } catch (RuntimeException e) {
            return Double.NaN; // database unreachable: report unknown rather than zero
        }
    }

    @Scheduled(fixedDelay = 250)
    synchronized void flush() {
        List<Row> rows = jdbc.query(
                "SELECT id, msg_key, payload FROM outbox ORDER BY id LIMIT 500",
                (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3)));
        for (Row row : rows) {
            try {
                kafka.send(Topics.POSTED, row.key(), row.payload()).get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                return; // broker trouble: leave the rows, retry on the next tick
            }
            jdbc.update("DELETE FROM outbox WHERE id = ?", row.id());
        }
    }
}

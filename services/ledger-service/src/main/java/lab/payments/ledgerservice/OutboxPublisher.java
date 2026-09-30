package lab.payments.ledgerservice;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import lab.payments.common.Topics;
import org.springframework.beans.factory.annotation.Value;
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
    private final boolean asyncSend;

    OutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, MeterRegistry meters,
            @Value("${lab.tuning.f01:false}") boolean asyncSend) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.asyncSend = asyncSend;
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

    @Scheduled(fixedDelayString = "${lab.outbox.poll-ms:250}")
    synchronized void flush() {
        List<Row> rows = jdbc.query(
                "SELECT id, msg_key, payload FROM outbox ORDER BY id LIMIT 500",
                (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3)));
        if (asyncSend) {
            flushAsync(rows);
            return;
        }
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

    /**
     * F-01 (tuned): send the batch without waiting per record and delete only the acknowledged rows.
     * Unacknowledged rows stay in the outbox and are retried; the gateway's status update is idempotent.
     */
    private void flushAsync(List<Row> rows) {
        ConcurrentLinkedQueue<Long> acked = new ConcurrentLinkedQueue<>();
        List<CompletableFuture<?>> pending = new ArrayList<>();
        for (Row row : rows) {
            pending.add(kafka.send(Topics.POSTED, row.key(), row.payload()).whenComplete((result, error) -> {
                if (error == null) {
                    acked.add(row.id());
                }
            }));
        }
        try {
            CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0])).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // broker trouble: unacknowledged rows stay for the next tick
        } finally {
            if (!acked.isEmpty()) {
                List<Long> ids = new ArrayList<>(acked);
                jdbc.update(con -> {
                    var ps = con.prepareStatement("DELETE FROM outbox WHERE id = ANY(?)");
                    ps.setArray(1, con.createArrayOf("bigint", ids.toArray()));
                    return ps;
                });
            }
        }
    }
}

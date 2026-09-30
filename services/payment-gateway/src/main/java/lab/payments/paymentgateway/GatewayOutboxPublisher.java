package lab.payments.paymentgateway;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lab.payments.common.TraceCarrier;
import org.springframework.beans.factory.ObjectProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lab.payments.common.Topics;
import org.springframework.beans.factory.annotation.Value;
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

    private record Row(long id, String key, String payload, String trace) {
    }

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final boolean asyncSend;
    private final Tracer tracer;
    private final Propagator propagator;

    GatewayOutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, MeterRegistry meters,
            @Value("${lab.tuning.f01:false}") boolean asyncSend,
            ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer.getIfAvailable();
        this.propagator = propagator.getIfAvailable();
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.asyncSend = asyncSend;
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
        List<Row> rows = jdbc.query("SELECT id, msg_key, payload, trace FROM outbox ORDER BY id LIMIT 500",
                (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        List<Long> sent = new ArrayList<>();
        try {
            if (asyncSend) {
                sendAsync(rows, sent);
            } else {
                for (Row row : rows) {
                    send(row).get(10, TimeUnit.SECONDS);
                    sent.add(row.id());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // broker trouble: keep the unsent rows and retry on the next tick
        } finally {
            deleteSent(sent);
        }
    }

    /**
     * F-01 (tuned): send the whole batch without waiting per record; the callback records each ack.
     * Only acknowledged rows are deleted, so a failed send is retried on the next tick. Partial
     * failure can reorder events, which is safe because the ledger orders by debtor sequence.
     */
    private void sendAsync(List<Row> rows, List<Long> sent) throws Exception {
        ConcurrentLinkedQueue<Long> acked = new ConcurrentLinkedQueue<>();
        List<CompletableFuture<?>> pending = new ArrayList<>();
        for (Row row : rows) {
            pending.add(send(row).whenComplete((result, error) -> {
                if (error == null) {
                    acked.add(row.id());
                }
            }));
        }
        try {
            CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0])).get(30, TimeUnit.SECONDS);
        } finally {
            sent.addAll(acked);
        }
    }

    /** The send runs inside a span whose parent is the trace of the request that wrote the row. */
    private CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> send(Row row) {
        return TraceCarrier.within(tracer, propagator, row.trace(), "gateway.outbox.publish",
                () -> kafka.send(Topics.INITIATED, row.key(), row.payload()));
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

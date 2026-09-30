package lab.payments.ledgerservice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lab.payments.common.EventLog;
import lab.payments.common.Json;
import lab.payments.common.PaymentValidated;
import lab.payments.common.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * F-04 (tuned): batch listener. Never lets an exception escape to the container's error handler,
 * because for a batch listener that handler would dead-letter EVERY record of the batch and leave
 * sequence gaps for many debtors. Instead:
 *  - unparseable records go to the DLT individually, before any database work;
 *  - transient database errors (deadlock, connection loss, lock set changed) retry the whole batch
 *    with capped backoff for as long as it takes (the batch is idempotent; the offset is committed only
 *    after it succeeds);
 *  - a persistent failure falls back to one transaction per record, and only the record that still
 *    fails is dead-lettered.
 */
@Component
@ConditionalOnProperty(name = "lab.tuning.f04", havingValue = "true")
class BatchValidatedListener {

    private static final Logger log = LoggerFactory.getLogger(BatchValidatedListener.class);

    private record Parsed(ConsumerRecord<String, String> record, PaymentValidated event) {
    }

    private final BatchLedgerProcessor processor;
    private final KafkaTemplate<String, String> kafka;

    BatchValidatedListener(BatchLedgerProcessor processor, KafkaTemplate<String, String> kafka) {
        this.processor = processor;
        this.kafka = kafka;
    }

    @KafkaListener(topics = Topics.VALIDATED, batch = "true")
    void onBatch(List<ConsumerRecord<String, String>> records) throws InterruptedException {
        List<Parsed> parsed = new ArrayList<>();
        for (ConsumerRecord<String, String> record : records) {
            try {
                PaymentValidated event = Json.read(record.value(), PaymentValidated.class);
                EventLog.event(log, "payment validated", event.paymentId().toString(), record::value); // F-09
                parsed.add(new Parsed(record, event));
            } catch (RuntimeException e) {
                deadLetter(record, "unparseable: " + e.getMessage());
            }
        }
        if (parsed.isEmpty()) {
            return;
        }
        try {
            processWithRetry(parsed.stream().map(Parsed::event).toList(), parsed.get(0).record().partition(), traceparents(parsed));
        } catch (InterruptedException e) {
            throw e;
        } catch (RuntimeException batchFailure) {
            log.warn("batch of {} failed persistently ({}); falling back to one transaction per record",
                    parsed.size(), batchFailure.toString());
            for (Parsed p : parsed) {
                try {
                    processWithRetry(List.of(p.event()), p.record().partition(), traceparents(List.of(p)));
                } catch (RuntimeException recordFailure) {
                    deadLetter(p.record(), "failed: " + recordFailure);
                }
            }
        }
    }

    /** Each record's own W3C traceparent header, by payment, so the outcome continues that payment's trace. */
    private static Map<UUID, String> traceparents(List<Parsed> parsed) {
        Map<UUID, String> result = new HashMap<>();
        for (Parsed p : parsed) {
            var header = p.record().headers().lastHeader("traceparent");
            if (header != null) {
                result.put(p.event().paymentId(), new String(header.value(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private void processWithRetry(List<PaymentValidated> events, int shardHint, Map<UUID, String> traceparents)
            throws InterruptedException {
        long delayMs = 200;
        while (true) {
            try {
                processor.process(events, shardHint, traceparents);
                return;
            } catch (RuntimeException e) {
                if (!isTransient(e)) {
                    throw e;
                }
                log.warn("transient failure applying a batch of {} ({}); retrying in {} ms",
                        events.size(), e.toString(), delayMs);
                Thread.sleep(delayMs);
                delayMs = Math.min(delayMs * 2, 5_000);
            }
        }
    }

    private static boolean isTransient(RuntimeException e) {
        return e instanceof TransientDataAccessException
                || e instanceof DataAccessResourceFailureException
                || e instanceof CannotCreateTransactionException
                || e instanceof LedgerService.LockSetChangedException;
    }

    private void deadLetter(ConsumerRecord<String, String> record, String reason) {
        log.error("dead-lettering {}-{}@{}: {}", record.topic(), record.partition(), record.offset(), reason);
        try {
            kafka.send(new ProducerRecord<>(Topics.VALIDATED + ".DLT", record.partition(), record.key(),
                    record.value())).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            throw new IllegalStateException("could not dead-letter the record", e);
        }
    }
}

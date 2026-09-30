package lab.payments.ledgerservice;

import lab.payments.common.EventLog;
import lab.payments.common.Json;
import lab.payments.common.PaymentValidated;
import lab.payments.common.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Baseline (F-04 anti-pattern): one record, one transaction. Replaced by BatchValidatedListener when tuned. */
@Component
@ConditionalOnProperty(name = "lab.tuning.f04", havingValue = "false", matchIfMissing = true)
class ValidatedListener {

    private static final Logger log = LoggerFactory.getLogger(ValidatedListener.class);

    private final LedgerService ledger;
    private final OutboxPublisher outbox;

    ValidatedListener(LedgerService ledger, OutboxPublisher outbox) {
        this.ledger = ledger;
        this.outbox = outbox;
    }

    /** The offset is committed only after the DB transaction commits (default BATCH ack). */
    @KafkaListener(topics = Topics.VALIDATED)
    void onValidated(String payload) {
        PaymentValidated event = Json.read(payload, PaymentValidated.class);
        EventLog.event(log, "payment validated", event.paymentId().toString(), () -> payload); // F-09
        ledger.handle(event);
        outbox.flush();
    }
}

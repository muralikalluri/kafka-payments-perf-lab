package lab.payments.validationservice;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import lab.payments.common.EventLog;
import lab.payments.common.Json;
import lab.payments.common.PaymentInitiated;
import lab.payments.common.PaymentValidated;
import lab.payments.common.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
class InitiatedListener {

    private static final Logger log = LoggerFactory.getLogger(InitiatedListener.class);

    private final ValidationService validation;
    private final KafkaTemplate<String, String> kafka;

    private final MeterRegistry meters;
    private final boolean keyByDebtor;

    InitiatedListener(ValidationService validation, KafkaTemplate<String, String> kafka,
            MeterRegistry meters, @Value("${lab.tuning.f12:false}") boolean keyByDebtor) {
        this.keyByDebtor = keyByDebtor;
        this.validation = validation;
        this.kafka = kafka;
        this.meters = meters;
    }

    /** At-least-once: a redelivery re-emits the same eventId; the ledger dedups on paymentId. */
    @KafkaListener(topics = Topics.INITIATED)
    void onInitiated(String payload) throws Exception {
        PaymentInitiated event = Json.read(payload, PaymentInitiated.class);
        EventLog.event(log, "payment initiated", event.paymentId().toString(), () -> payload); // F-09
        PaymentValidated result = validation.validate(event);
        EventLog.event(log, "payment validated", result.paymentId().toString(), () -> Json.write(result)); // F-09
        // F-12: keyed by merchant id in baseline, by debtor account id in tuned.
        String key = keyByDebtor ? result.debtorAccountId() : result.merchantId();
        kafka.send(Topics.VALIDATED, key, Json.write(result)).get(10, TimeUnit.SECONDS);
        meters.counter("validation.results", "outcome", result.outcome().name()).increment();
    }
}

package lab.payments.validationservice;

import java.util.concurrent.TimeUnit;
import lab.payments.common.Json;
import lab.payments.common.PaymentInitiated;
import lab.payments.common.PaymentValidated;
import lab.payments.common.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
class InitiatedListener {

    private final ValidationService validation;
    private final KafkaTemplate<String, String> kafka;

    InitiatedListener(ValidationService validation, KafkaTemplate<String, String> kafka) {
        this.validation = validation;
        this.kafka = kafka;
    }

    /** At-least-once: a redelivery re-emits the same eventId; the ledger dedups on paymentId. */
    @KafkaListener(topics = Topics.INITIATED)
    void onInitiated(String payload) throws Exception {
        PaymentInitiated event = Json.read(payload, PaymentInitiated.class);
        PaymentValidated result = validation.validate(event);
        // F-12: keyed by merchant id in baseline.
        kafka.send(Topics.VALIDATED, result.merchantId(), Json.write(result)).get(10, TimeUnit.SECONDS);
    }
}

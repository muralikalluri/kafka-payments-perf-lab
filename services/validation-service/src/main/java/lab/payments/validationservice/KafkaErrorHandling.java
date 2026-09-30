package lab.payments.validationservice;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
class KafkaErrorHandling {

    /** Poison messages go to payments.initiated.DLT after a few retries. */
    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template) {
        ExponentialBackOff backOff = new ExponentialBackOff(200, 2.0);
        backOff.setMaxElapsedTime(30_000); // ride out a database or broker blip before dead-lettering
        DefaultErrorHandler handler =
                new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template,
                        // Spring's default destination is "<topic>-dlt"; our topics are "<topic>.DLT".
                        (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition())),
                backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class); // unparseable payload
        return handler;
    }
}

package lab.payments.common;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps scheduled polling out of the traces. The outbox publishers and workers run every few tens of milliseconds, and
 * Spring would otherwise record every execution as a one-span trace, burying the payment traces people look for.
 */
@Configuration
public class TracingNoise {

    @Bean
    ObservationPredicate ignoreScheduledTasks() {
        return (name, context) -> !name.startsWith("tasks.scheduled");
    }
}

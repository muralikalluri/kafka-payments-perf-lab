package lab.payments.validationservice;

import io.micrometer.core.instrument.MeterRegistry;
import lab.payments.common.AccountUpdated;
import lab.payments.common.Json;
import lab.payments.common.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * F-13 (tuned): every validation instance sees every account.updated event (its consumer group id is
 * unique per instance) and tombstones the cached copy. A failed invalidation is counted, not retried:
 * the TTL is the backstop, which bounds how long a stale value can survive.
 */
@Component
@ConditionalOnProperty(name = "lab.tuning.f13", havingValue = "true")
class AccountUpdatedListener {

    private static final Logger log = LoggerFactory.getLogger(AccountUpdatedListener.class);

    static final String GROUP_PREFIX = "validation-cache-";

    private final AccountCache cache;
    private final MeterRegistry meters;

    AccountUpdatedListener(AccountCache cache, MeterRegistry meters) {
        this.cache = cache;
        this.meters = meters;
    }

    @KafkaListener(topics = Topics.ACCOUNT_UPDATED, groupId = GROUP_PREFIX + "${random.uuid}",
            properties = "auto.offset.reset=latest")
    void onUpdated(String payload) {
        AccountUpdated event = Json.read(payload, AccountUpdated.class);
        try {
            boolean applied = cache.invalidate(event.accountId(), event.version());
            meters.counter("cache.invalidations", "result", applied ? "applied" : "skipped").increment();
        } catch (RuntimeException e) {
            meters.counter("cache.invalidations", "result", "error").increment();
            log.warn("could not invalidate {} v{}: {}", event.accountId(), event.version(), e.toString());
        }
    }
}

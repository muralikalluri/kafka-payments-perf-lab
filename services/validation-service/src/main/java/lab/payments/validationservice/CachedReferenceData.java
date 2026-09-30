package lab.payments.validationservice;

import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * F-13 (tuned): cache-aside over the F-08 projection source. Accounts (status, limits, ownership) come
 * from Redis when present; a miss is loaded from Postgres by ONE caller per key at a time
 * (single-flight), so a hot key expiring does not send every thread to the database at once.
 * Layering: FX rates stay in the F-08 Caffeine cache (tiny, static, no invalidation event); account data
 * lives in Redis so several validation instances share it and account.updated events can invalidate it.
 */
@Component
@Primary
@ConditionalOnProperty(name = "lab.tuning.f13", havingValue = "true")
public class CachedReferenceData implements ReferenceData {

    private static final Logger log = LoggerFactory.getLogger(CachedReferenceData.class);

    private final ProjectionReferenceData database;
    private final AccountCache cache;
    private final MeterRegistry meters;
    private final Map<String, CompletableFuture<Optional<AccountSnapshot>>> inFlight = new ConcurrentHashMap<>();

    CachedReferenceData(ProjectionReferenceData database, AccountCache cache, MeterRegistry meters) {
        this.database = database;
        this.cache = cache;
        this.meters = meters;
    }

    @Override
    public Map<String, AccountSnapshot> accounts(Collection<String> ids) {
        Map<String, AccountSnapshot> result = new HashMap<>();
        for (String id : ids) {
            Optional<AccountSnapshot> snapshot = cache.lookup(id);
            if (snapshot.isEmpty()) {
                snapshot = load(id);
            }
            snapshot.ifPresent(s -> result.put(id, s));
        }
        return result;
    }

    @Override
    public Optional<BigDecimal> fxRateToUsd(String currency) {
        return database.fxRateToUsd(currency);
    }

    private Optional<AccountSnapshot> load(String id) {
        CompletableFuture<Optional<AccountSnapshot>> mine = new CompletableFuture<>();
        CompletableFuture<Optional<AccountSnapshot>> existing = inFlight.putIfAbsent(id, mine);
        if (existing != null) {
            meters.counter("cache.singleflight.waits").increment();
            return existing.join(); // follower: wait for the leader's result (exceptions are not cached)
        }
        try {
            // Double-checked: another leader may have populated the cache between our miss and now.
            Optional<AccountSnapshot> recheck = cache.peek(id);
            if (recheck.isPresent()) {
                mine.complete(recheck);
                return recheck;
            }
            meters.counter("cache.loads", "source", "postgres").increment();
            Optional<AccountSnapshot> loaded = Optional.ofNullable(database.accounts(List.of(id)).get(id));
            loaded.ifPresent(this::populateQuietly); // never cache "not found"
            mine.complete(loaded);
            return loaded;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(id, mine);
        }
    }

    private void populateQuietly(AccountSnapshot snapshot) {
        try {
            cache.populate(snapshot);
        } catch (RuntimeException e) {
            log.debug("could not populate the cache for {}: {}", snapshot.id(), e.toString());
        }
    }
}

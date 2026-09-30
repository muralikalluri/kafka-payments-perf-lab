package lab.payments.validationservice;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import lab.payments.common.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * F-13 (tuned): Redis L2 for account snapshots (status, ownership, currency, per-transaction limit).
 *
 * Every entry carries the account's version. Two Lua scripts keep the cache from going backwards:
 *  - populate writes only if the cached version is older (or a tombstone of a version not newer than
 *    the loaded one), so a reader that loaded version 1 before an update cannot overwrite version 2;
 *  - invalidate writes a tombstone {version} only if the cached version is older, so a late or repeated
 *    event cannot evict newer data, and an event that arrives before the entry exists still blocks a
 *    slower, stale populate.
 * "Not found" is never cached. Redis errors fail open (callers fall back to Postgres); after repeated
 * failures a small circuit breaker skips Redis for a few seconds instead of paying the timeout each time.
 */
@Component
@ConditionalOnProperty(name = "lab.tuning.f13", havingValue = "true")
public class AccountCache {

    private static final Logger log = LoggerFactory.getLogger(AccountCache.class);

    private static final String POPULATE = """
            local cur = redis.call('GET', KEYS[1])
            local v = tonumber(ARGV[2])
            if cur then
              local d = cjson.decode(cur)
              local cv = tonumber(d.version)
              if cv > v then return 0 end
              if cv == v and not d.tombstone then return 0 end
            end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[3]))
            return 1""";

    private static final String INVALIDATE = """
            local cur = redis.call('GET', KEYS[1])
            local v = tonumber(ARGV[2])
            if cur then
              local d = cjson.decode(cur)
              if tonumber(d.version) >= v then return 0 end
            end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[3]))
            return 1""";

    private record Entry(long version, boolean tombstone, AccountSnapshot snapshot) {
    }

    private static final int BREAKER_THRESHOLD = 3;
    private static final long BREAKER_OPEN_MS = 5_000;

    private final StringRedisTemplate redis;
    private final MeterRegistry meters;
    private final String namespace;
    private final long ttlSeconds;
    private final DefaultRedisScript<Long> populateScript = new DefaultRedisScript<>(POPULATE, Long.class);
    private final DefaultRedisScript<Long> invalidateScript = new DefaultRedisScript<>(INVALIDATE, Long.class);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long breakerOpenUntil;

    AccountCache(StringRedisTemplate redis, MeterRegistry meters,
            @Value("${lab.cache.namespace:v1}") String namespace,
            @Value("${lab.cache.ttl-seconds:60}") long ttlSeconds) {
        this.redis = redis;
        this.meters = meters;
        this.namespace = namespace;
        this.ttlSeconds = ttlSeconds;
    }

    private String key(String accountId) {
        return namespace + ":acct:" + accountId;
    }

    /** A cached snapshot, or empty on miss, tombstone or Redis trouble. */
    public Optional<AccountSnapshot> lookup(String accountId) {
        return lookup(accountId, true);
    }

    /**
     * Same as lookup but without touching the hit/miss metrics: used by the single-flight leader to
     * re-check the cache before going to Postgres, so the hit ratio reflects real requests only.
     */
    Optional<AccountSnapshot> peek(String accountId) {
        return lookup(accountId, false);
    }

    private Optional<AccountSnapshot> lookup(String accountId, boolean countMetrics) {
        if (breakerOpen()) {
            if (countMetrics) {
                count("skipped");
            }
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(key(accountId));
            success();
            if (json == null) {
                if (countMetrics) {
                    count("miss");
                }
                return Optional.empty();
            }
            Entry entry = Json.read(json, Entry.class);
            if (entry.tombstone()) {
                if (countMetrics) {
                    count("miss");
                }
                return Optional.empty();
            }
            if (countMetrics) {
                count("hit");
            }
            return Optional.of(entry.snapshot());
        } catch (RuntimeException e) {
            failure(e);
            if (countMetrics) {
                count("error");
            }
            return Optional.empty();
        }
    }

    /** Stores a freshly loaded snapshot unless the cache already holds something newer. */
    public boolean populate(AccountSnapshot snapshot) {
        return run(populateScript, snapshot.id(),
                Json.write(new Entry(snapshot.version(), false, snapshot)), snapshot.version(), true);
    }

    /** Applies an account.updated event: the cached copy (if older than version) becomes a tombstone. */
    public boolean invalidate(String accountId, long version) {
        // Never skipped because the breaker is open: a missed invalidation is the dangerous failure.
        return run(invalidateScript, accountId, Json.write(new Entry(version, true, null)), version, false);
    }

    private boolean run(DefaultRedisScript<Long> script, String accountId, String value, long version,
            boolean respectBreaker) {
        if (respectBreaker && breakerOpen()) {
            return false;
        }
        try {
            Long applied = redis.execute(script, List.of(key(accountId)), value, String.valueOf(version),
                    String.valueOf(ttlSeconds));
            success();
            return applied != null && applied == 1L;
        } catch (RuntimeException e) {
            failure(e);
            throw e;
        }
    }

    /** True while the circuit breaker is skipping Redis after repeated failures. */
    public boolean isCircuitOpen() {
        return breakerOpen();
    }

    private boolean breakerOpen() {
        return System.currentTimeMillis() < breakerOpenUntil;
    }

    private void success() {
        consecutiveFailures.set(0);
    }

    private void failure(RuntimeException e) {
        if (consecutiveFailures.incrementAndGet() >= BREAKER_THRESHOLD) {
            breakerOpenUntil = System.currentTimeMillis() + BREAKER_OPEN_MS;
            consecutiveFailures.set(0);
            log.warn("Redis unavailable ({}); skipping the cache for {} ms", e.toString(), BREAKER_OPEN_MS);
        }
    }

    private void count(String result) {
        meters.counter("cache.gets", "layer", "redis", "result", result).increment();
    }

    Duration ttl() {
        return Duration.ofSeconds(ttlSeconds);
    }
}

package lab.payments.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JSON for events. Unknown fields are ignored so consumers tolerate schema evolution.
 *
 * F-09 (baseline, intentional): a new ObjectMapper is built for every message. Building a mapper and registering
 * modules is far more expensive than using one. Tuned (lab.tuning.f09) shares a single mapper, which is thread-safe
 * once configured. {@link #mappersCreated()} exposes the difference so a test can check it.
 */
public final class Json {

    private static final AtomicLong CREATED = new AtomicLong();
    private static volatile boolean sharedMapper = false;
    private static final ObjectMapper SHARED = build();

    private Json() {
    }

    private static ObjectMapper build() {
        CREATED.incrementAndGet();
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    private static ObjectMapper mapper() {
        return sharedMapper ? SHARED : build(); // F-09: baseline builds one per message
    }

    /** Switches between the shared mapper (tuned) and a mapper per message (baseline). */
    public static void useSharedMapper(boolean shared) {
        sharedMapper = shared;
    }

    public static boolean isSharedMapper() {
        return sharedMapper;
    }

    /** How many mappers have been built in this JVM (one, plus one per message when not shared). */
    public static long mappersCreated() {
        return CREATED.get();
    }

    public static String write(Object value) {
        try {
            return mapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return mapper().readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot parse " + type.getSimpleName(), e);
        }
    }
}

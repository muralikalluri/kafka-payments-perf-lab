package lab.payments.common;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Carries a trace across an outbox. A row written in one thread is published later by another, and the publisher has no
 * current span, so without help the trace would end at the outbox. The writer stores the W3C traceparent with the row;
 * the publisher restores it as the parent of a short span around the send, so the Kafka producer and every downstream
 * consumer continue the same trace. All methods are no-ops when tracing is not available.
 */
public final class TraceCarrier {

    private static final String TRACEPARENT = "traceparent";

    private TraceCarrier() {
    }

    /** The current span's traceparent, or null when there is none. */
    public static String capture(Tracer tracer, Propagator propagator) {
        if (tracer == null || propagator == null) {
            return null;
        }
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    /** Runs the action inside a span whose parent is the stored context (or just runs it when there is none). */
    public static <T> T within(Tracer tracer, Propagator propagator, String traceparent, String name, Supplier<T> action) {
        if (tracer == null || propagator == null || traceparent == null) {
            return action.get();
        }
        Span span = propagator.extract(Map.of(TRACEPARENT, traceparent), (carrier, key) -> carrier.get(key)).name(name).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return action.get();
        } finally {
            span.end();
        }
    }
}

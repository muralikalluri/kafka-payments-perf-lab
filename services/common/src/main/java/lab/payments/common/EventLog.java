package lab.payments.common;

import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Event logging.
 *
 * F-09 (baseline, intentional): every event is logged at INFO with its full JSON payload. That is a lot of text
 * per message, written on the hot path, and it puts account numbers and amounts into the logs. Tuned
 * (lab.tuning.f09) logs only identifiers, at DEBUG, and leaves INFO for things an operator needs to see.
 */
public final class EventLog {

    private static volatile boolean verbose = true;

    private EventLog() {
    }

    public static void verbose(boolean value) {
        verbose = value;
    }

    /** True when {@link #event} would write anything, so callers can skip building its arguments. */
    public static boolean isActive(Logger log) {
        return verbose || log.isDebugEnabled();
    }

    /** @param payload evaluated only when the full payload is going to be logged */
    public static void event(Logger log, String stage, String paymentId, Supplier<String> payload) {
        if (verbose) {
            log.info("{} {}", stage, payload.get());
        } else if (log.isDebugEnabled()) {
            log.debug("{} paymentId={}", stage, paymentId);
        }
    }
}

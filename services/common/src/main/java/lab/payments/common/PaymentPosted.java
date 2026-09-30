package lab.payments.common;

import java.time.Instant;
import java.util.UUID;

/** Terminal event: the only place the gateway learns the final outcome. */
public record PaymentPosted(
        int schemaVersion,
        UUID eventId,
        UUID causationId,
        UUID paymentId,
        String clientId,
        String merchantId,
        String debtorAccountId,
        Outcome outcome,
        String reasonCode,
        Instant postedAt) {

    public static final int SCHEMA_VERSION = 1;

    public enum Outcome { POSTED, REJECTED }
}

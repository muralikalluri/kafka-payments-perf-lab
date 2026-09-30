package lab.payments.common;

import java.time.Instant;
import java.util.UUID;

/** Emitted by validation for every payment, including rejections, so debtorSeq never has gaps. */
public record PaymentValidated(
        int schemaVersion,
        UUID eventId,
        UUID causationId,
        UUID paymentId,
        String clientId,
        Outcome outcome,
        String reasonCode,
        String debtorAccountId,
        String creditorAccountId,
        String merchantId,
        long amountMinor,
        String currency,
        long debtorSeq,
        Instant validatedAt) {

    public static final int SCHEMA_VERSION = 1;

    public enum Outcome { VALID, REJECTED }
}

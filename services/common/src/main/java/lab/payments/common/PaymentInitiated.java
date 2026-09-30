package lab.payments.common;

import java.time.Instant;
import java.util.UUID;

/** Emitted by the gateway. debtorSeq orders payments per debtor account (see ADR-0002). */
public record PaymentInitiated(
        int schemaVersion,
        UUID eventId,
        UUID paymentId,
        String clientId,
        String idempotencyKey,
        String debtorAccountId,
        String creditorAccountId,
        String merchantId,
        long amountMinor,
        String currency,
        long debtorSeq,
        Instant createdAt) {

    public static final int SCHEMA_VERSION = 1;
}

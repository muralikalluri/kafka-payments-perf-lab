package lab.payments.paymentgateway;

import java.util.UUID;

/** The stored "original result" returned for every replay of the same idempotency key. */
public record PaymentAccepted(UUID paymentId, String status) {
}

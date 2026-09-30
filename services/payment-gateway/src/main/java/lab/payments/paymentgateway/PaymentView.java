package lab.payments.paymentgateway;

import java.util.UUID;

public record PaymentView(
        UUID paymentId,
        String status,
        String reasonCode,
        String debtorAccountId,
        String creditorAccountId,
        long amountMinor,
        String currency) {
}

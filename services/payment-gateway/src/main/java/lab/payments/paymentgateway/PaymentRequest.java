package lab.payments.paymentgateway;

public record PaymentRequest(
        String debtorAccountId,
        String creditorAccountId,
        String merchantId,
        Long amountMinor,
        String currency) {

    boolean isValid() {
        return notBlank(debtorAccountId) && notBlank(creditorAccountId) && notBlank(merchantId)
                && amountMinor != null && amountMinor > 0
                && currency != null && currency.matches("[A-Z]{3}");
    }

    /** Canonical form hashed for idempotency-key reuse detection. */
    String canonical() {
        return String.join("|", debtorAccountId, creditorAccountId, merchantId,
                String.valueOf(amountMinor), currency);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}

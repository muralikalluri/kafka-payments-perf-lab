package lab.payments.validationservice;

/**
 * What validation needs to know about an account. perTxLimitUsdMinor is Long.MAX_VALUE when unlimited.
 * version increases with every change to the account's reference data (0 when the source has none).
 */
public record AccountSnapshot(String id, String clientId, String currency, String status,
        long perTxLimitUsdMinor, long version) {
}

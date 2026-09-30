package lab.payments.validationservice;

/** What validation needs to know about an account. perTxLimitUsdMinor is Long.MAX_VALUE when unlimited. */
record AccountSnapshot(String id, String clientId, String currency, String status, long perTxLimitUsdMinor) {
}

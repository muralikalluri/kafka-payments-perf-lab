package lab.payments.common;

/** Published when reference data for an account changes; consumers invalidate cached copies (F-13). */
public record AccountUpdated(String accountId, long version, String status) {
}

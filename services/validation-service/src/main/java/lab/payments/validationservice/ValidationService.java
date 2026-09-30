package lab.payments.validationservice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lab.payments.common.Ids;
import lab.payments.common.PaymentInitiated;
import lab.payments.common.PaymentValidated;
import lab.payments.common.PaymentValidated.Outcome;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class ValidationService {

    static final String SANCTIONED_PREFIX = "SANC-";

    private final ReferenceData reference;
    private final TransactionTemplate tx;
    private final boolean wrapInTransaction;

    ValidationService(ReferenceData reference, PlatformTransactionManager transactionManager,
            @Value("${lab.tuning.f08:false}") boolean tuned) {
        this.reference = reference;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setReadOnly(true); // as the baseline's @Transactional(readOnly = true) was
        // Baseline: one read-only transaction around the whole validation (the JPA implementation needs it
        // for lazy loading). Tuned reference data uses plain JDBC and, with the Redis cache in front, most
        // validations touch no database at all, so holding a pooled connection for the whole call would
        // defeat the pool sizing.
        this.wrapInTransaction = !tuned;
    }

    /**
     * Every payment yields exactly one PaymentValidated (rejections included) so the debtor
     * sequence has no gaps downstream. Where reference data comes from is the ReferenceData
     * implementation's business: the baseline one carries the F-08 / F-13 anti-patterns.
     */
    PaymentValidated validate(PaymentInitiated e) {
        String reason = wrapInTransaction ? tx.execute(status -> reject(e)) : reject(e);
        return new PaymentValidated(PaymentValidated.SCHEMA_VERSION,
                Ids.eventId(e.paymentId(), "validated"), e.eventId(), e.paymentId(), e.clientId(),
                reason == null ? Outcome.VALID : Outcome.REJECTED, reason,
                e.debtorAccountId(), e.creditorAccountId(), e.merchantId(), e.amountMinor(),
                e.currency(), e.debtorSeq(), Instant.now());
    }

    private String reject(PaymentInitiated e) {
        if (e.amountMinor() <= 0 || e.currency() == null || e.currency().length() != 3) {
            return "INVALID_SCHEMA";
        }
        if (e.debtorAccountId().equals(e.creditorAccountId())) {
            return "SAME_ACCOUNT";
        }
        if (e.creditorAccountId().startsWith(SANCTIONED_PREFIX)) {
            return "SANCTIONS_HIT"; // sanctions-list stub
        }
        Map<String, AccountSnapshot> found = reference.accounts(List.of(e.debtorAccountId(), e.creditorAccountId()));
        AccountSnapshot debtor = found.get(e.debtorAccountId());
        if (debtor == null) {
            return "DEBTOR_NOT_FOUND";
        }
        if (!debtor.clientId().equals(e.clientId())) {
            return "DEBTOR_NOT_OWNED";
        }
        AccountSnapshot creditor = found.get(e.creditorAccountId());
        if (creditor == null) {
            return "CREDITOR_NOT_FOUND";
        }
        if (!"ACTIVE".equals(debtor.status()) || !"ACTIVE".equals(creditor.status())) {
            return "ACCOUNT_INACTIVE";
        }
        if (!e.currency().equals(debtor.currency()) || !e.currency().equals(creditor.currency())) {
            return "CURRENCY_MISMATCH";
        }
        BigDecimal fx = reference.fxRateToUsd(e.currency()).orElse(null);
        if (fx == null) {
            return "FX_RATE_MISSING";
        }
        long amountUsdMinor = BigDecimal.valueOf(e.amountMinor())
                .multiply(fx).setScale(0, RoundingMode.HALF_UP).longValueExact();
        if (amountUsdMinor > debtor.perTxLimitUsdMinor()) {
            return "LIMIT_EXCEEDED";
        }
        return null;
    }
}

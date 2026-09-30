package lab.payments.validationservice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import lab.payments.common.Ids;
import lab.payments.common.PaymentInitiated;
import lab.payments.common.PaymentValidated;
import lab.payments.common.PaymentValidated.Outcome;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ValidationService {

    static final String SANCTIONED_PREFIX = "SANC-";

    private final AccountRepository accounts;
    private final FxRateRepository fxRates;

    ValidationService(AccountRepository accounts, FxRateRepository fxRates) {
        this.accounts = accounts;
        this.fxRates = fxRates;
    }

    /**
     * Every payment yields exactly one PaymentValidated (rejections included) so the debtor
     * sequence has no gaps downstream.
     *
     * F-13 (baseline, intentional): account status, limits and FX rates are read from Postgres on
     * every message, with no cache.
     * F-08 (baseline, intentional): each account load is followed by lazy queries for its limits
     * (N+1), including for the creditor, whose limits are never needed.
     */
    @Transactional(readOnly = true)
    PaymentValidated validate(PaymentInitiated e) {
        String reason = reject(e);
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
        Account debtor = accounts.findById(e.debtorAccountId()).orElse(null);
        if (debtor == null) {
            return "DEBTOR_NOT_FOUND";
        }
        if (!debtor.getClientId().equals(e.clientId())) {
            return "DEBTOR_NOT_OWNED";
        }
        Account creditor = accounts.findById(e.creditorAccountId()).orElse(null);
        if (creditor == null) {
            return "CREDITOR_NOT_FOUND";
        }
        long perTxLimit = debtor.getLimits().stream()
                .filter(l -> "PER_TX".equals(l.getLimitType()))
                .mapToLong(AccountLimit::getAmountUsdMinor).min().orElse(Long.MAX_VALUE);
        creditor.getLimits().size(); // F-08: needless lazy load of the creditor's limits
        if (!"ACTIVE".equals(debtor.getStatus()) || !"ACTIVE".equals(creditor.getStatus())) {
            return "ACCOUNT_INACTIVE";
        }
        if (!e.currency().equals(debtor.getCurrency())
                || !e.currency().equals(creditor.getCurrency())) {
            return "CURRENCY_MISMATCH";
        }
        FxRate fx = fxRates.findById(e.currency()).orElse(null);
        if (fx == null) {
            return "FX_RATE_MISSING";
        }
        long amountUsdMinor = BigDecimal.valueOf(e.amountMinor())
                .multiply(fx.getRateToUsd()).setScale(0, RoundingMode.HALF_UP).longValueExact();
        if (amountUsdMinor > perTxLimit) {
            return "LIMIT_EXCEEDED";
        }
        return null;
    }
}

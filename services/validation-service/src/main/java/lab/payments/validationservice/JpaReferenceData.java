package lab.payments.validationservice;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Baseline reference data.
 *
 * F-13 (baseline, intentional): account status, limits and FX rates are read from Postgres on every
 * call, with no cache.
 * F-08 (baseline, intentional): each account load is followed by a lazy query for its limits (N+1),
 * including for the creditor, whose limits are never needed.
 */
@Component
@ConditionalOnProperty(name = "lab.tuning.f08", havingValue = "false", matchIfMissing = true)
class JpaReferenceData implements ReferenceData {

    private final AccountRepository accounts;
    private final FxRateRepository fxRates;

    JpaReferenceData(AccountRepository accounts, FxRateRepository fxRates) {
        this.accounts = accounts;
        this.fxRates = fxRates;
    }

    @Override
    public Map<String, AccountSnapshot> accounts(Collection<String> ids) {
        Map<String, AccountSnapshot> result = new LinkedHashMap<>();
        for (String id : ids) {
            accounts.findById(id).ifPresent(a -> {
                long perTx = a.getLimits().stream()                       // F-08: lazy query per account
                        .filter(l -> "PER_TX".equals(l.getLimitType()))
                        .mapToLong(AccountLimit::getAmountUsdMinor).min().orElse(Long.MAX_VALUE);
                result.put(id, new AccountSnapshot(a.getId(), a.getClientId(), a.getCurrency(), a.getStatus(), perTx));
            });
        }
        return result;
    }

    @Override
    public Optional<BigDecimal> fxRateToUsd(String currency) {
        return fxRates.findById(currency).map(FxRate::getRateToUsd);
    }
}

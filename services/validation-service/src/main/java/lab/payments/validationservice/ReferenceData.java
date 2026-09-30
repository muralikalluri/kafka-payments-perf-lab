package lab.payments.validationservice;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** Where validation reads accounts, limits and FX rates from. Missing accounts are absent from the map. */
interface ReferenceData {

    Map<String, AccountSnapshot> accounts(Collection<String> ids);

    Optional<BigDecimal> fxRateToUsd(String currency);
}

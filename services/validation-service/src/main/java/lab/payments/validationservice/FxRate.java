package lab.payments.validationservice;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "fx_rates")
class FxRate {

    @Id
    private String currency;
    private BigDecimal rateToUsd;

    protected FxRate() {
    }

    BigDecimal getRateToUsd() { return rateToUsd; }
}

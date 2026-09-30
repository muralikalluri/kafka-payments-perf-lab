package lab.payments.validationservice;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "account_limits")
class AccountLimit {

    @Id
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private Account account;

    private String limitType;
    private long amountUsdMinor;

    protected AccountLimit() {
    }

    String getLimitType() { return limitType; }
    long getAmountUsdMinor() { return amountUsdMinor; }
}

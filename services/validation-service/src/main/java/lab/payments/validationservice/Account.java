package lab.payments.validationservice;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "accounts")
class Account {

    @Id
    private String id;
    private String clientId;
    private String currency;
    private String status;

    @OneToMany(mappedBy = "account", fetch = FetchType.LAZY)
    private List<AccountLimit> limits = new ArrayList<>();

    protected Account() {
    }

    String getId() { return id; }
    String getClientId() { return clientId; }
    String getCurrency() { return currency == null ? null : currency.trim(); }
    String getStatus() { return status; }
    List<AccountLimit> getLimits() { return limits; }
}

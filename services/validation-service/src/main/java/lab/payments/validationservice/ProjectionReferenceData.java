package lab.payments.validationservice;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * F-08 (tuned): one projection query joins accounts to their per-transaction limit (no lazy loads, no
 * limits fetched for accounts that do not need them), and the FX rates table, which is tiny and
 * changes rarely, is cached in Caffeine with a short TTL. Uses account_limits_account_idx (V100).
 */
@Component
@ConditionalOnProperty(name = "lab.tuning.f08", havingValue = "true")
public class ProjectionReferenceData implements ReferenceData {

    private static final String SQL = """
            SELECT a.id, a.client_id, a.currency, a.status,
                   MIN(l.amount_usd_minor) FILTER (WHERE l.limit_type = 'PER_TX') AS per_tx,
                   a.version
            FROM accounts a LEFT JOIN account_limits l ON l.account_id = a.id
            WHERE a.id = ANY(?)
            GROUP BY a.id, a.client_id, a.currency, a.status, a.version""";

    private final JdbcTemplate jdbc;
    private final Cache<String, Optional<BigDecimal>> fx = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60)).maximumSize(64).build();

    ProjectionReferenceData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, AccountSnapshot> accounts(Collection<String> ids) {
        Map<String, AccountSnapshot> result = new HashMap<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement(SQL);
            ps.setArray(1, con.createArrayOf("text", ids.toArray()));
            return ps;
        }, rs -> {
            long perTx = rs.getObject("per_tx") == null ? Long.MAX_VALUE : rs.getLong("per_tx");
            result.put(rs.getString("id"), new AccountSnapshot(rs.getString("id"), rs.getString("client_id"),
                    rs.getString("currency").trim(), rs.getString("status"), perTx,
                    rs.getLong("version")));
        });
        return result;
    }

    @Override
    public Optional<BigDecimal> fxRateToUsd(String currency) {
        return fx.get(currency, c -> jdbc.query("SELECT rate_to_usd FROM fx_rates WHERE currency = ?",
                (rs, i) -> rs.getBigDecimal(1), c).stream().findFirst());
    }
}

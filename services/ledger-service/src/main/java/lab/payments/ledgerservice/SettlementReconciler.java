package lab.payments.ledgerservice;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic settlement reconciliation (F-11). It moves no money: because each payment's settlement legs net to zero in
 * the same transaction, the aggregate position over all settlement accounts must be zero and so must every shard. The
 * job records the aggregate in settlement_snapshots and warns (and exports a gauge) if anything is not zero.
 */
@Component
class SettlementReconciler {

    private static final Logger log = LoggerFactory.getLogger(SettlementReconciler.class);

    private final JdbcTemplate jdbc;
    private final AtomicLong nonzero = new AtomicLong();
    private final AtomicLong position = new AtomicLong();

    SettlementReconciler(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        Gauge.builder("ledger.settlement.nonzero", nonzero, AtomicLong::get).register(meters);
        Gauge.builder("ledger.settlement.position", position, AtomicLong::get).register(meters);
    }

    @Scheduled(fixedDelayString = "${lab.settlement.reconcile-ms:10000}")
    void reconcile() {
        try {
            long[] row = jdbc.queryForObject("""
                    SELECT count(*), count(*) FILTER (WHERE balance_minor <> 0), COALESCE(sum(balance_minor), 0)
                    FROM accounts WHERE id LIKE 'SETTLE-%'""", (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)});
            nonzero.set(row[1]);
            position.set(row[2]);
            jdbc.update("INSERT INTO settlement_snapshots(accounts, nonzero, position_minor) VALUES (?, ?, ?)",
                    row[0], row[1], row[2]);
            if (row[1] != 0 || row[2] != 0) {
                log.warn("settlement reconciliation: {} of {} accounts not zero, position {}", row[1], row[0], row[2]);
            }
        } catch (RuntimeException e) {
            log.debug("settlement reconciliation skipped: {}", e.toString());
        }
    }
}

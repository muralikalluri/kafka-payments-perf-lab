package lab.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** F-08: baseline loads limits lazily per account (2 scans per validation); tuned joins once. */
class ReferenceDataAccessTest {

    private static final String CLIENT = "client-refdata";
    private static final int PAYMENTS = 20;

    private static long limitsScans() {
        // pg_stat counters are flushed asynchronously; callers wait for them to settle.
        return Lab.JDBC.queryForObject("""
                SELECT COALESCE(seq_scan, 0) + COALESCE(idx_scan, 0) FROM pg_stat_user_tables
                WHERE schemaname = 'validation' AND relname = 'account_limits'""", Long.class);
    }

    @Test
    void accountLimitsAreQueriedAtMostOncePerValidationWhenTuned() throws Exception {
        String debtor = Lab.account(CLIENT, 100_000);
        String creditor = Lab.account(CLIENT, 0);
        Thread.sleep(1500);
        long before = limitsScans();

        for (int i = 0; i < PAYMENTS; i++) {
            assertThat(Lab.post(CLIENT, "k-" + UUID.randomUUID(), Lab.body(debtor, creditor, "MER-1", 1)).statusCode())
                    .isEqualTo(202);
        }
        Lab.await("payments terminal", () -> Lab.count(
                "SELECT count(*) FROM gateway.payments WHERE debtor_account_id = ? AND status_rank = 3", debtor) == PAYMENTS);
        if (!Lab.tuned()) {
            Lab.await("limits scan counter reflects two scans per validation", () -> limitsScans() - before >= 2L * PAYMENTS);
        }
        Thread.sleep(1500); // let any surplus scans show up before asserting an upper bound
        long delta = limitsScans() - before;
        if (Lab.tuned()) {
            // At most one join per validation (with the F-13 cache in front, far fewer). Stats counters flush
            // asynchronously and other tests may leave a few stray scans in the window, so allow some slack,
            // but nowhere near two per validation.
            assertThat(delta).isLessThan(Math.round(PAYMENTS * 1.5));
        } else {
            assertThat(delta).isGreaterThanOrEqualTo(2L * PAYMENTS); // lazy limits for debtor AND creditor
        }
    }
}

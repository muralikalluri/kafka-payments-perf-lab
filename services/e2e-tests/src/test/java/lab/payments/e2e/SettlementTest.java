package lab.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lab.payments.common.Json;
import lab.payments.common.PaymentValidated;
import lab.payments.common.Topics;
import org.junit.jupiter.api.Test;

/**
 * F-11: every payment posts debtor -> settlement -> creditor. Settlement nets to zero at every commit in both profiles;
 * baseline uses one settlement account, tuned spreads transactions over shard accounts.
 */
class SettlementTest {

    private static final String CLIENT = "client-settle";

    private static PaymentValidated validated(UUID paymentId, String debtor, String creditor, long amount, long seq, String currency) {
        return new PaymentValidated(PaymentValidated.SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(), paymentId, CLIENT,
                PaymentValidated.Outcome.VALID, null, debtor, creditor, "MER-1", amount, currency, seq, Instant.now());
    }

    private static String ledgerOnly(long balance) {
        String id = "T-" + UUID.randomUUID().toString().substring(0, 12);
        Lab.ledgerAccount(id, CLIENT, balance);
        return id;
    }

    private static int nonZeroSettlement() {
        return Lab.count("SELECT count(*) FROM ledger.accounts WHERE id LIKE 'SETTLE-%' AND balance_minor <> 0");
    }

    /** Concurrent payments across many debtors: four legs each, and every settlement account is zero afterwards. */
    @Test
    void settlementNetsToZeroAndPostsFourLegsPerPayment() throws Exception {
        List<String> debtors = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            debtors.add(Lab.account(CLIENT, 10_000));
        }
        String creditor = Lab.account(CLIENT, 0);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (String debtor : debtors) {
            for (int i = 0; i < 15; i++) {
                calls.add(() -> Lab.post(CLIENT, "s-" + UUID.randomUUID(), Lab.body(debtor, creditor, "MER-1", 1)).statusCode());
            }
        }
        for (Future<Integer> f : pool.invokeAll(calls)) {
            assertThat(f.get()).isEqualTo(202);
        }
        pool.shutdown();
        Lab.await("all terminal", () -> Lab.count(
                "SELECT count(*) FROM gateway.payments WHERE client_id = ? AND debtor_account_id = ANY(?::text[]) AND status_rank = 3",
                CLIENT, "{" + String.join(",", debtors) + "}") == debtors.size() * 15);

        assertThat(nonZeroSettlement()).as("every settlement account is zero").isZero();
        assertThat(Lab.count("""
                SELECT count(*) FROM (SELECT p.payment_id FROM ledger.ledger_payments p
                  LEFT JOIN ledger.postings s ON s.payment_id = p.payment_id
                  WHERE p.outcome = 'POSTED' AND p.debtor_account_id = ANY(?::text[])
                  GROUP BY p.payment_id HAVING count(s.id) <> ?) x""",
                "{" + String.join(",", debtors) + "}", Lab.postingsPerPayment())).as("four legs per posted payment").isZero();
        int shardsUsed = Lab.count("SELECT count(DISTINCT account_id) FROM ledger.postings WHERE account_id LIKE 'SETTLE-%' AND payment_id IN "
                + "(SELECT payment_id FROM ledger.ledger_payments WHERE debtor_account_id = ANY(?::text[]))",
                "{" + String.join(",", debtors) + "}");
        if (Lab.tuned()) {
            assertThat(shardsUsed).as("tuned spreads transactions over shard accounts").isGreaterThan(1);
        } else {
            assertThat(shardsUsed).as("baseline funnels everything through one settlement account").isEqualTo(1);
        }
    }

    /** Settlement accounts are not customer accounts: naming one as debtor or creditor is rejected. */
    @Test
    void settlementAccountsCannotBeUsedAsCustomerAccounts() {
        String customer = ledgerOnly(1000);
        UUID asDebtor = UUID.randomUUID();
        UUID asCreditor = UUID.randomUUID();
        Lab.produce(Topics.VALIDATED, "MER-1", Json.write(validated(asDebtor, "SETTLE-USD", customer, 10, 1, "USD")));
        Lab.produce(Topics.VALIDATED, "MER-1", Json.write(validated(asCreditor, customer, "SETTLE-USD", 10, 1, "USD")));
        Lab.await("both rejected", () -> Lab.count(
                "SELECT count(*) FROM ledger.ledger_payments WHERE payment_id IN (?, ?) AND reason_code = 'RESERVED_ACCOUNT'",
                asDebtor, asCreditor) == 2);
        // The reserved-creditor payment still consumed its debtor sequence number, so the debtor is not stuck.
        UUID next = UUID.randomUUID();
        String other = ledgerOnly(0);
        Lab.produce(Topics.VALIDATED, "MER-1", Json.write(validated(next, customer, other, 10, 2, "USD")));
        Lab.await("next payment applied", () -> Lab.count(
                "SELECT count(*) FROM ledger.ledger_payments WHERE payment_id = ? AND outcome = 'POSTED'", next) == 1);
        assertThat(nonZeroSettlement()).isZero();
    }

    /** A currency with no settlement account is rejected rather than posted through nothing. */
    @Test
    void missingSettlementAccountIsRejectedAndConsumesTheSequence() {
        String debtor = "T-" + UUID.randomUUID().toString().substring(0, 12);
        String creditor = "T-" + UUID.randomUUID().toString().substring(0, 12);
        for (String id : new String[] {debtor, creditor}) {
            Lab.JDBC.update("INSERT INTO ledger.accounts(id, client_id, currency, overdraft, balance_minor, daily_limit_minor) "
                    + "VALUES (?, ?, 'EUR', false, 1000, 1000000000)", id, CLIENT);
        }
        UUID eur = UUID.randomUUID();
        Lab.produce(Topics.VALIDATED, "MER-1", Json.write(validated(eur, debtor, creditor, 10, 1, "EUR")));
        Lab.await("rejected", () -> Lab.count(
                "SELECT count(*) FROM ledger.ledger_payments WHERE payment_id = ? AND reason_code = 'SETTLEMENT_ACCOUNT_MISSING'", eur) == 1);
        assertThat(Lab.count("SELECT count(*) FROM ledger.postings WHERE payment_id = ?", eur)).isZero();
        assertThat(Lab.JDBC.queryForObject("SELECT last_seq FROM ledger.accounts WHERE id = ?", Long.class, debtor)).isEqualTo(1L);
    }

    /** The reconciliation job records a zero position and no non-zero settlement account. */
    @Test
    void reconciliationReportsAZeroPosition() {
        Lab.await("a snapshot exists", () -> Lab.count("SELECT count(*) FROM ledger.settlement_snapshots") > 0);
        assertThat(Lab.count("SELECT count(*) FROM ledger.settlement_snapshots WHERE nonzero <> 0 OR position_minor <> 0")).isZero();
    }
}

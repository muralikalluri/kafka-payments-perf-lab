package lab.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import lab.payments.common.Ids;
import lab.payments.common.Json;
import lab.payments.common.PaymentValidated;
import lab.payments.common.Topics;
import org.junit.jupiter.api.Test;

/**
 * The outcome of a payment can reach the gateway before the gateway has a row for it: a gateway send whose transaction
 * rolled back (a phantom event) is applied by the ledger, the outcome arrives, the row is not there yet, and the client
 * then retries the same request. The retry's own event is a duplicate at the ledger. The ledger must re-publish the recorded
 * outcome, or that payment stays ACCEPTED for good.
 */
class LateRowTest {

    private static final String CLIENT = "client-late";

    @Test
    void outcomeThatArrivedBeforeTheGatewayRowStillReachesTheRow() throws Exception {
        String debtor = Lab.account(CLIENT, 1000);
        String creditor = Lab.account(CLIENT, 0);
        String key = "late-" + UUID.randomUUID();
        UUID paymentId = Ids.paymentId(CLIENT, key);

        // The outcome first, with no gateway row: the ledger applies it and its outcome event reaches a gateway that has nothing to update.
        PaymentValidated phantom = new PaymentValidated(PaymentValidated.SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(),
                paymentId, CLIENT, PaymentValidated.Outcome.VALID, null, debtor, creditor, "MER-1", 10, "USD", 1, Instant.now());
        Lab.produce(Topics.VALIDATED, "MER-1", Json.write(phantom));
        Lab.await("ledger applied it", () -> Lab.count("SELECT count(*) FROM ledger.ledger_payments WHERE payment_id = ?", paymentId) == 1);
        Lab.await("its outcome was published", () -> Lab.count("SELECT count(*) FROM ledger.outbox") == 0);
        Thread.sleep(1500);
        assertThat(Lab.count("SELECT count(*) FROM gateway.payments WHERE payment_id = ?", paymentId)).isZero();

        // Now the client's request arrives: the row is created, and the ledger sees a duplicate of a payment it already recorded.
        assertThat(Lab.post(CLIENT, key, Lab.body(debtor, creditor, "MER-1", 10)).statusCode()).isEqualTo(202);
        Lab.await("the row reaches its terminal status", () -> Lab.count(
                "SELECT count(*) FROM gateway.payments WHERE payment_id = ? AND status_rank = 3", paymentId) == 1);
        assertThat(Lab.count("SELECT count(*) FROM ledger.postings WHERE payment_id = ?", paymentId))
                .as("still applied exactly once").isEqualTo(Lab.postingsPerPayment());
    }
}

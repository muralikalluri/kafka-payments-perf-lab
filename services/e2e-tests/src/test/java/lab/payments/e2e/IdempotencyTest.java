package lab.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class IdempotencyTest {

    private static final String CLIENT = "client-idem";

    /** T1: concurrent duplicates yield one payment, one event and one ledger effect. */
    @Test
    void concurrentDuplicatePostsCreateExactlyOnePayment() throws Exception {
        String debtor = Lab.account(CLIENT, 10_000);
        String creditor = Lab.account(CLIENT, 0);
        String key = "key-" + UUID.randomUUID();
        String body = Lab.body(debtor, creditor, "MER-1", 100);

        ExecutorService pool = Executors.newFixedThreadPool(50);
        List<Callable<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            calls.add(() -> Lab.post(CLIENT, key, body));
        }
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (Future<HttpResponse<String>> f : pool.invokeAll(calls)) {
            responses.add(f.get());
        }
        pool.shutdown();

        assertThat(responses).allMatch(r -> r.statusCode() == 202);
        Set<String> bodies = responses.stream().map(HttpResponse::body).collect(Collectors.toSet());
        assertThat(bodies).hasSize(1);
        assertThat(Lab.count("SELECT count(*) FROM gateway.payments WHERE idempotency_key = ?", key)).isEqualTo(1);

        UUID paymentId = UUID.fromString(Lab.JDBC.queryForObject(
                "SELECT payment_id::text FROM gateway.payments WHERE idempotency_key = ?", String.class, key));
        Lab.await("payment terminal", () -> Lab.count(
                "SELECT count(*) FROM gateway.payments WHERE payment_id = ? AND status_rank = 3", paymentId) == 1);
        assertThat(Lab.count("SELECT count(*) FROM ledger.ledger_payments WHERE payment_id = ?", paymentId)).isEqualTo(1);
        assertThat(Lab.count("SELECT count(*) FROM ledger.postings WHERE payment_id = ?", paymentId)).isEqualTo(Lab.postingsPerPayment());
    }

    /** T2 + input validation + tenant isolation on reads (T11 part). */
    @Test
    void keyReuseWithDifferentBodyIsRejectedAndClientsAreIsolated() {
        String debtor = Lab.account(CLIENT, 10_000);
        String creditor = Lab.account(CLIENT, 0);
        String key = "key-" + UUID.randomUUID();

        HttpResponse<String> first = Lab.post(CLIENT, key, Lab.body(debtor, creditor, "MER-1", 100));
        assertThat(first.statusCode()).isEqualTo(202);

        HttpResponse<String> reused = Lab.post(CLIENT, key, Lab.body(debtor, creditor, "MER-1", 999));
        assertThat(reused.statusCode()).isEqualTo(422);
        assertThat(Lab.count("SELECT count(*) FROM gateway.payments WHERE idempotency_key = ?", key)).isEqualTo(1);

        HttpResponse<String> otherClient = Lab.post("client-other", key, Lab.body(debtor, creditor, "MER-1", 100));
        assertThat(otherClient.statusCode()).isEqualTo(202);
        assertThat(otherClient.body()).isNotEqualTo(first.body());

        String paymentId = first.body().replaceAll(".*\"paymentId\":\"([^\"]+)\".*", "$1");
        assertThat(Lab.get(CLIENT, paymentId).statusCode()).isEqualTo(200);
        assertThat(Lab.get("client-nosy", paymentId).statusCode()).isEqualTo(404);

        assertThat(Lab.post(CLIENT, "k-" + UUID.randomUUID(), Lab.body(debtor, creditor, "MER-1", -5)).statusCode())
                .isEqualTo(400);
    }
}

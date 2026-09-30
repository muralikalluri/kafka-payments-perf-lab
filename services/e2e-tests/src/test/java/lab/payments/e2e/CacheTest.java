package lab.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lab.payments.validationservice.AccountCache;
import lab.payments.validationservice.AccountSnapshot;
import lab.payments.validationservice.CachedReferenceData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** F-13: Redis cache-aside with event invalidation. Tuned profile only (the baseline has no cache). */
class CacheTest {

    private static final String CLIENT = "client-cache";
    private static final String ADMIN_TOKEN = "lab-admin-local-only";

    @BeforeAll
    static void onlyWhenTuned() {
        assumeTrue(Lab.tuned(), "the account cache only exists in the tuned profile");
        Lab.awaitInvalidationConsumer();
    }

    private static HttpResponse<String> adminPut(String accountId, String token, String status) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + Lab.VALIDATION_PORT + "/lab-admin/accounts/" + accountId))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"status\":\"" + status + "\"}"));
        if (token != null) {
            request.header("X-Admin-Token", token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String reasonOf(String key) {
        List<String> reasons = Lab.JDBC.queryForList(
                "SELECT reason_code FROM gateway.payments WHERE payment_id = ? AND status_rank = 3",
                String.class, lab.payments.common.Ids.paymentId(CLIENT, key));
        return reasons.isEmpty() ? null : reasons.get(0); // an approved payment has a null reason
    }

    private static double counter(String name, String... tags) {
        var counter = Lab.VALIDATION.getBean(MeterRegistry.class).find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private String pay(String debtor, String creditor) {
        String key = "k-" + UUID.randomUUID();
        assertThat(Lab.post(CLIENT, key, Lab.body(debtor, creditor, "MER-1", 1)).statusCode()).isEqualTo(202);
        Lab.await("terminal", () -> Lab.count(
                "SELECT count(*) FROM gateway.payments WHERE payment_id = ? AND status_rank = 3",
                lab.payments.common.Ids.paymentId(CLIENT, key)) == 1);
        return key;
    }

    /** Blocking an account through the admin endpoint stops approvals well before the TTL would. */
    @Test
    void blockingAnAccountInvalidatesTheCachedCopy() throws Exception {
        String debtor = Lab.account(CLIENT, 100_000);
        String creditor = Lab.account(CLIENT, 0);
        for (int i = 0; i < 3; i++) {
            assertThat(reasonOf(pay(debtor, creditor))).isNull(); // approved, and the account is now cached
        }
        HttpResponse<String> blocked = adminPut(debtor, ADMIN_TOKEN, "BLOCKED");
        assertThat(blocked.statusCode()).isEqualTo(200);

        // The TTL is 60 s; seeing the rejection within 20 s proves the event invalidated the entry.
        long deadline = System.currentTimeMillis() + 20_000;
        String reason = null;
        while (System.currentTimeMillis() < deadline && !"ACCOUNT_INACTIVE".equals(reason)) {
            reason = reasonOf(pay(debtor, creditor));
        }
        assertThat(reason).isEqualTo("ACCOUNT_INACTIVE");

        assertThat(adminPut(debtor, ADMIN_TOKEN, "ACTIVE").statusCode()).isEqualTo(200);
        long deadline2 = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline2 && reason != null) {
            reason = reasonOf(pay(debtor, creditor));
        }
        assertThat(reason).isNull(); // unblocking is picked up the same way
    }

    @Test
    void adminEndpointRequiresTheToken() throws Exception {
        String account = Lab.account(CLIENT, 0);
        assertThat(adminPut(account, null, "BLOCKED").statusCode()).isEqualTo(403);
        assertThat(adminPut(account, "wrong-token", "BLOCKED").statusCode()).isEqualTo(403);
        assertThat(Lab.JDBC.queryForObject("SELECT status FROM validation.accounts WHERE id = ?", String.class, account))
                .isEqualTo("ACTIVE");
    }

    @Test
    void repeatedValidationsAreServedFromTheCache() {
        String debtor = Lab.account(CLIENT, 100_000);
        String creditor = Lab.account(CLIENT, 0);
        double hitsBefore = counter("cache.gets", "result", "hit");
        double loadsBefore = counter("cache.loads", "source", "postgres");
        for (int i = 0; i < 10; i++) {
            pay(debtor, creditor);
        }
        double hits = counter("cache.gets", "result", "hit") - hitsBefore;
        double loads = counter("cache.loads", "source", "postgres") - loadsBefore;
        assertThat(loads).isLessThanOrEqualTo(2); // one per account, then cached
        assertThat(hits).isGreaterThanOrEqualTo(16); // 10 payments x 2 accounts, minus the first loads
    }

    /**
     * The race: a slow reader loaded version 1 from Postgres, meanwhile version 2 was published and its
     * event invalidated the key; the slow reader then tries to cache version 1. It must lose.
     */
    @Test
    void aStaleReaderCannotOverwriteANewerVersionAfterInvalidation() {
        AccountCache cache = Lab.VALIDATION.getBean(AccountCache.class);
        String id = "T-race-" + UUID.randomUUID().toString().substring(0, 8);
        AccountSnapshot v1 = new AccountSnapshot(id, CLIENT, "USD", "ACTIVE", 1_000_000L, 1);
        AccountSnapshot v2 = new AccountSnapshot(id, CLIENT, "USD", "BLOCKED", 1_000_000L, 2);

        assertThat(cache.invalidate(id, 2)).isTrue();          // event for v2 arrives first (tombstone)
        assertThat(cache.populate(v1)).isFalse();               // the stale reader loses
        assertThat(cache.lookup(id)).isEmpty();                 // tombstone reads as a miss
        assertThat(cache.populate(v2)).isTrue();                // the fresh load wins
        assertThat(cache.lookup(id)).contains(v2);
        assertThat(cache.populate(v1)).isFalse();               // and stays won
        assertThat(cache.invalidate(id, 1)).isFalse();          // a late, older event cannot evict it
        assertThat(cache.lookup(id)).contains(v2);
    }

    /** A hot key missing from the cache is loaded from Postgres once, however many threads ask. */
    @Test
    void concurrentMissesAreLoadedOnce() throws Exception {
        CachedReferenceData data = Lab.VALIDATION.getBean(CachedReferenceData.class);
        String id = Lab.account(CLIENT, 0);
        double loadsBefore = counter("cache.loads", "source", "postgres");

        ExecutorService pool = Executors.newFixedThreadPool(50);
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            calls.add(() -> data.accounts(List.of(id)).containsKey(id));
        }
        for (Future<Boolean> f : pool.invokeAll(calls)) {
            assertThat(f.get()).isTrue();
        }
        pool.shutdown();
        // One leader loads; late arrivals find the entry (leaders re-check the cache before loading).
        assertThat(counter("cache.loads", "source", "postgres") - loadsBefore).isEqualTo(1);
    }

    /** Redis stopping must not stop validation: it fails open to Postgres. */
    @Test
    void validationContinuesWhenRedisIsDown() {
        String debtor = Lab.account(CLIENT, 100_000);
        String creditor = Lab.account(CLIENT, 0);
        pay(debtor, creditor); // warm path
        Lab.pauseRedis();
        try {
            for (int i = 0; i < 3; i++) {
                assertThat(reasonOf(pay(debtor, creditor))).isNull();
            }
        } finally {
            Lab.unpauseRedis();
        }
        // Leave the circuit breaker closed for whichever test runs next.
        AccountCache cache = Lab.VALIDATION.getBean(AccountCache.class);
        Lab.await("circuit breaker closed again", () -> {
            cache.lookup("T-probe");
            return !cache.isCircuitOpen();
        });
    }
}

package lab.payments.gatling;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.percent;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The same workload as load/k6 (lib.js), for people who prefer Gatling: an open arrival rate of payments, 80% normal,
 * 10% replays of an earlier idempotency key and 10% that fail validation, with half the traffic on one large merchant.
 * Parameters: -Drate (arrivals per second), -Dseconds, -DbaseUrl. Like the k6 load scenarios it posts and does not
 * wait for the pipeline; end-to-end latency is read from the database by run-benchmark.sh.
 */
public class PaymentsSimulation extends Simulation {

    private record Request(String key, String body) {
    }

    private static final AtomicReference<Request> LAST = new AtomicReference<>();

    private static String number(int lo, int hi) {
        return String.format("%04d", ThreadLocalRandom.current().nextInt(lo, hi + 1));
    }

    private static Request newPayment(boolean failing) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String merchant = r.nextDouble() < 0.5 ? "MER-001" : String.format("MER-%03d", r.nextInt(2, 21));
        String body = String.format(
                "{\"debtorAccountId\":\"ACC-%s\",\"creditorAccountId\":\"%s\",\"merchantId\":\"%s\",\"amountMinor\":%d,\"currency\":\"USD\"}",
                number(1, 800), failing ? "SANC-0001" : "ACC-" + number(801, 990), merchant, r.nextInt(1, 101));
        return new Request("gatling-" + UUID.randomUUID(), body);
    }

    private final double rate = Double.parseDouble(System.getProperty("rate", "20"));
    private final int seconds = Integer.parseInt(System.getProperty("seconds", "30"));
    private final String baseUrl = System.getProperty("baseUrl", "http://localhost:8080");

    private final HttpProtocolBuilder protocol = http.baseUrl(baseUrl)
            .acceptHeader("application/json").contentTypeHeader("application/json").header("X-Client-Id", "client-demo");

    private final ScenarioBuilder payments = scenario("payments").randomSwitch().on(
            percent(80.0).then(exec(session -> {
                Request r = newPayment(false);
                LAST.set(r);
                return session.set("key", r.key()).set("body", r.body());
            }).exec(post("create payment"))),
            percent(10.0).then(exec(session -> {
                Request r = LAST.get() != null ? LAST.get() : newPayment(false);
                return session.set("key", r.key()).set("body", r.body());
            }).exec(post("replay payment"))),
            percent(10.0).then(exec(session -> {
                Request r = newPayment(true);
                return session.set("key", r.key()).set("body", r.body());
            }).exec(post("failing payment"))));

    private static io.gatling.javaapi.http.HttpRequestActionBuilder post(String name) {
        return http(name).post("/payments").header("Idempotency-Key", "#{key}")
                .body(StringBody("#{body}")).check(status().is(202));
    }

    {
        setUp(payments.injectOpen(constantUsersPerSec(rate).during(Duration.ofSeconds(seconds))))
                .protocols(protocol)
                .assertions(global().failedRequests().percent().lt(0.1));
    }
}

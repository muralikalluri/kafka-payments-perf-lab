package lab.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lab.payments.common.Ids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tracing (opt-in: -Dlab.tracing=true starts a Jaeger container): one payment is one trace across the gateway,
 * validation and ledger, including across the outbox publishers and the Kafka hops.
 */
class TracingTest {

    private static final String CLIENT = "client-trace";
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void onlyWhenTracing() {
        assumeTrue(Lab.TRACING, "run with -Dlab.tracing=true to start Jaeger");
    }

    private static JsonNode traces(String service) throws Exception {
        String url = "http://localhost:" + Lab.JAEGER.getMappedPort(16686) + "/api/traces?service=" + service + "&limit=50&lookback=1h";
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(response.body()).path("data");
    }

    private static Set<String> services(JsonNode trace) {
        Set<String> names = new HashSet<>();
        trace.path("processes").fields().forEachRemaining(e -> names.add(e.getValue().path("serviceName").asText()));
        return names;
    }

    @Test
    void onePaymentIsOneTraceAcrossTheServices() throws Exception {
        String debtor = Lab.account(CLIENT, 1000);
        String creditor = Lab.account(CLIENT, 0);
        String key = "trace-" + UUID.randomUUID();
        assertThat(Lab.post(CLIENT, key, Lab.body(debtor, creditor, "MER-1", 10)).statusCode()).isEqualTo(202);
        UUID paymentId = Ids.paymentId(CLIENT, key);
        Lab.await("terminal", () -> Lab.count(
                "SELECT count(*) FROM gateway.payments WHERE payment_id = ? AND status_rank = 3", paymentId) == 1);

        Set<String> expected = Set.of("payment-gateway", "validation-service", "ledger-service");
        JsonNode[] found = new JsonNode[1];
        java.util.List<String> seen = new java.util.ArrayList<>();
        try {
            Lab.await("a trace spanning " + expected + " appears in Jaeger", () -> {
                try {
                    seen.clear();
                    for (JsonNode trace : traces("payment-gateway")) {
                        Set<String> names = services(trace);
                        seen.add(names + " (" + trace.path("spans").size() + " spans)");
                        if (names.containsAll(expected)) {
                            found[0] = trace;
                            return true;
                        }
                    }
                } catch (Exception e) {
                    return false;
                }
                return false;
            });
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + "; traces seen for payment-gateway: " + seen, e);
        }
        JsonNode trace = found[0];
        assertThat(trace.path("spans").size()).as("spans in the trace").isGreaterThanOrEqualTo(6);
        boolean postSpan = false;
        for (JsonNode span : trace.path("spans")) {
            postSpan |= span.path("operationName").asText().toLowerCase().contains("post");
        }
        assertThat(postSpan).as("the trace starts from the POST /payments request").isTrue();
    }
}

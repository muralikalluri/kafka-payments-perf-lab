package lab.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** F-10: the gateway serves requests on virtual threads when tuned and on platform threads in baseline. */
class ThreadingTest {

    private static final String CLIENT = "client-f10";

    private static double requests(String kind) {
        var counter = Lab.GATEWAY.getBean(MeterRegistry.class).find("gateway.requests.threads").tag("kind", kind).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void requestThreadsFollowTheProfile() {
        String debtor = Lab.account(CLIENT, 1000);
        String creditor = Lab.account(CLIENT, 0);
        double virtualBefore = requests("virtual");
        double platformBefore = requests("platform");
        for (int i = 0; i < 5; i++) {
            assertThat(Lab.post(CLIENT, "t-" + UUID.randomUUID(), Lab.body(debtor, creditor, "MER-1", 1)).statusCode()).isEqualTo(202);
        }
        assertThat(Lab.GATEWAY.getEnvironment().getProperty("spring.threads.virtual.enabled", "false"))
                .isEqualTo(Lab.tuned() ? "true" : "false");
        if (Lab.tuned()) {
            assertThat(requests("virtual") - virtualBefore).isEqualTo(5);
            assertThat(requests("platform") - platformBefore).isZero();
        } else {
            assertThat(requests("platform") - platformBefore).isEqualTo(5);
            assertThat(requests("virtual") - virtualBefore).isZero();
        }
    }
}

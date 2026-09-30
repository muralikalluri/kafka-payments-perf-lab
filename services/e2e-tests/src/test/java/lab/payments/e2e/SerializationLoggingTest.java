package lab.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.UUID;
import lab.payments.common.Json;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** F-09: mapper per message and full-payload INFO logging in baseline; shared mapper and quiet INFO when tuned. */
class SerializationLoggingTest {

    private static final String CLIENT = "client-f09";
    private static final int PAYMENTS = 15;

    @Test
    void mapperAndLoggingFollowTheProfile() {
        String debtor = Lab.account(CLIENT, 100_000);
        String creditor = Lab.account(CLIENT, 0);

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        long mappersBefore = Json.mappersCreated();
        try {
            for (int i = 0; i < PAYMENTS; i++) {
                String key = "f09-" + UUID.randomUUID();
                assertThat(Lab.post(CLIENT, key, Lab.body(debtor, creditor, "MER-1", 1)).statusCode()).isEqualTo(202);
            }
            Lab.await("payments terminal", () -> Lab.count(
                    "SELECT count(*) FROM gateway.payments WHERE debtor_account_id = ? AND status_rank = 3", debtor) == PAYMENTS);
        } finally {
            root.detachAppender(appender);
        }
        long mappersCreated = Json.mappersCreated() - mappersBefore;
        List<ILoggingEvent> payloadLogs = List.copyOf(appender.list).stream()
                .filter(e -> e.getLoggerName().startsWith("lab.payments"))
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO))
                .filter(e -> e.getFormattedMessage().contains(debtor))
                .toList();

        assertThat(Json.isSharedMapper()).isEqualTo(Lab.tuned());
        if (Lab.tuned()) {
            assertThat(mappersCreated).as("no mapper is built per message").isZero();
            assertThat(payloadLogs).as("no payload at INFO").isEmpty();
        } else {
            assertThat(mappersCreated).as("a mapper is built for every message").isGreaterThanOrEqualTo(PAYMENTS);
            // gateway request, initiated, validated (validation), validated (ledger), posted: at least three stages per payment
            assertThat(payloadLogs.size()).as("full payloads at INFO").isGreaterThanOrEqualTo(3 * PAYMENTS);
        }
    }
}

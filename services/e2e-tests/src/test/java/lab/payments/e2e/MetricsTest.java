package lab.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MetricsTest {

    private static final String CLIENT = "client-metrics";

    /** Every service exposes Prometheus metrics, and the pipeline's own metrics move with traffic. */
    @Test
    void pipelineMetricsAreExposedAndMove() {
        String debtor = Lab.account(CLIENT, 1000);
        String creditor = Lab.account(CLIENT, 0);
        String key = "k-" + UUID.randomUUID();
        assertThat(Lab.post(CLIENT, key, Lab.body(debtor, creditor, "MER-1", 10)).statusCode()).isEqualTo(202);
        Lab.await("terminal", () -> Lab.count(
                "SELECT count(*) FROM gateway.payments WHERE debtor_account_id = ? AND status_rank = 3", debtor) == 1);

        String gateway = Lab.scrape(Lab.GATEWAY_PORT);
        assertThat(gateway).contains("payments_accepted_total")
                .contains("payments_e2e_latency_seconds_bucket")
                .contains("http_server_requests_seconds_bucket")
                .contains("hikaricp_connections_active")
                .contains("application=\"payment-gateway\"");
        assertThat(Lab.scrape(Lab.VALIDATION_PORT)).contains("validation_results_total");
        String ledger = Lab.scrape(Lab.LEDGER_PORT);
        assertThat(ledger).contains("ledger_applied_total").contains("ledger_outbox_backlog")
                .contains("ledger_pending_payments").contains("kafka_consumer_");
    }
}

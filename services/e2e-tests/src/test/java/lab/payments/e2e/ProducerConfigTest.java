package lab.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;

/** F-01: effective producer settings follow the profile (read from the running services). */
class ProducerConfigTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> producerConfig(ConfigurableApplicationContext ctx) {
        return ctx.getBean(KafkaTemplate.class).getProducerFactory().getConfigurationProperties();
    }

    @Test
    void lingerBatchAndCompressionFollowTheProfile() {
        for (ConfigurableApplicationContext ctx : new ConfigurableApplicationContext[] {Lab.GATEWAY, Lab.VALIDATION, Lab.LEDGER}) {
            Map<String, Object> config = producerConfig(ctx);
            String service = ctx.getEnvironment().getProperty("spring.application.name");
            if (Lab.tuned()) {
                // Validation waits for each ack before committing the offset, so it does not linger (ADR-0004).
                String expectedLinger = "validation-service".equals(service) ? "0" : "10";
                assertThat(String.valueOf(config.get("linger.ms"))).as(service + " linger.ms").isEqualTo(expectedLinger);
                assertThat(config.get("compression.type")).as(service + " compression").isEqualTo("lz4");
                assertThat(String.valueOf(config.get("batch.size"))).as(service + " batch.size").isEqualTo("65536");
            } else {
                // Baseline leaves these at Kafka's defaults (linger 0, no compression, 16 KiB batches);
                // the gateway states linger and compression explicitly, the others just don't set them.
                assertThat(config.get("linger.ms")).as(service + " linger.ms").isIn(null, "0", 0);
                assertThat(config.get("compression.type")).as(service + " compression").isIn(null, "none");
                assertThat(config.get("batch.size")).as(service + " batch.size").isNull();
            }
        }
    }

    /** F-02: idempotence and in-flight requests are set explicitly in both profiles (never left to client defaults). */
    @Test
    void idempotenceAndInFlightFollowTheProfile() {
        for (ConfigurableApplicationContext ctx : new ConfigurableApplicationContext[] {Lab.GATEWAY, Lab.VALIDATION, Lab.LEDGER}) {
            Map<String, Object> config = producerConfig(ctx);
            String service = ctx.getEnvironment().getProperty("spring.application.name");
            assertThat(config.get("enable.idempotence")).as(service + " enable.idempotence must be explicit").isNotNull();
            assertThat(config.get("max.in.flight.requests.per.connection")).as(service + " in-flight must be explicit").isNotNull();
            assertThat(String.valueOf(config.get("enable.idempotence"))).as(service)
                    .isEqualTo(Lab.tuned() ? "true" : "false");
            assertThat(String.valueOf(config.get("max.in.flight.requests.per.connection"))).as(service)
                    .isEqualTo(Lab.tuned() ? "5" : "1");
            assertThat(String.valueOf(config.get("acks"))).as(service + " acks").isEqualTo("all");
        }
    }
}

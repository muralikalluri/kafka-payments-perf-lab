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
                assertThat(String.valueOf(config.get("linger.ms"))).as(service + " linger.ms").isEqualTo("10");
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
}

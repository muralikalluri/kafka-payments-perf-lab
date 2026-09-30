package lab.payments.ledgerservice;

import lab.payments.common.JsonTuning;
import lab.payments.common.TopicsConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Import({TopicsConfig.class, JsonTuning.class})
public class LedgerServiceApplication {

    /** Unique config name so all services can share one classpath in the e2e tests. */
    public static final String CONFIG_NAME = "spring.config.name=ledger-service";

    public static void main(String[] args) {
        new SpringApplicationBuilder(LedgerServiceApplication.class)
                .properties(CONFIG_NAME)
                .run(args);
    }
}

package lab.payments.validationservice;

import lab.payments.common.TopicsConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(TopicsConfig.class)
public class ValidationServiceApplication {

    /** Unique config name so all services can share one classpath in the e2e tests. */
    public static final String CONFIG_NAME = "spring.config.name=validation-service";

    public static void main(String[] args) {
        new SpringApplicationBuilder(ValidationServiceApplication.class)
                .properties(CONFIG_NAME)
                .run(args);
    }
}

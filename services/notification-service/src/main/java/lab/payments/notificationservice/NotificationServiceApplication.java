package lab.payments.notificationservice;

import lab.payments.common.JsonTuning;
import lab.payments.common.TopicsConfig;
import lab.payments.common.TracingNoise;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Import({TopicsConfig.class, JsonTuning.class, TracingNoise.class})
public class NotificationServiceApplication {

    /** Unique config name so all services can share one classpath in the e2e tests. */
    public static final String CONFIG_NAME = "spring.config.name=notification-service";

    public static void main(String[] args) {
        new SpringApplicationBuilder(NotificationServiceApplication.class)
                .properties(CONFIG_NAME)
                .run(args);
    }
}

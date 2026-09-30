package lab.payments.common;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/** Creates the pipeline topics. RF=1 is a lab limitation (single broker). */
@Configuration
public class TopicsConfig {

    @Bean
    KafkaAdmin.NewTopics labTopics(@Value("${lab.topics.partitions:3}") int partitions) {
        return new KafkaAdmin.NewTopics(
                topic(Topics.INITIATED, partitions),
                topic(Topics.VALIDATED, partitions),
                topic(Topics.POSTED, partitions),
                topic(Topics.INITIATED + ".DLT", partitions),
                topic(Topics.VALIDATED + ".DLT", partitions));
    }

    private static NewTopic topic(String name, int partitions) {
        return TopicBuilder.name(name).partitions(partitions).replicas(1).build();
    }
}

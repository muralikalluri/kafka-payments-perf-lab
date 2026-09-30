package lab.payments.common;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Creates the pipeline topics. The replication factor is a property of the environment, not of the profile: 1 for
 * the single-broker Testcontainers suite, 3 against the compose cluster (where min.insync.replicas is 2).
 */
@Configuration
public class TopicsConfig {

    @Bean
    KafkaAdmin.NewTopics labTopics(@Value("${lab.topics.partitions:3}") int partitions,
            @Value("${lab.topics.replicas:1}") int replicas) {
        return new KafkaAdmin.NewTopics(
                topic(Topics.INITIATED, partitions, replicas),
                topic(Topics.VALIDATED, partitions, replicas),
                topic(Topics.POSTED, partitions, replicas),
                topic(Topics.ACCOUNT_UPDATED, partitions, replicas),
                topic(Topics.INITIATED + ".DLT", partitions, replicas),
                topic(Topics.VALIDATED + ".DLT", partitions, replicas));
    }

    private static NewTopic topic(String name, int partitions, int replicas) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replicas)
                .config("min.insync.replicas", String.valueOf(Math.min(2, replicas))).build();
    }
}

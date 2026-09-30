package lab.payments.e2e;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import lab.payments.ledgerservice.LedgerServiceApplication;
import lab.payments.paymentgateway.PaymentGatewayApplication;
import lab.payments.validationservice.ValidationServiceApplication;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * One shared environment for every e2e test class: Postgres + Kafka containers and the three real
 * services running in this JVM. Profile comes from -Dlab.profile (default: baseline).
 */
final class Lab {

    static final String PROFILE = System.getProperty("lab.profile", "baseline");
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");
    static final JdbcTemplate JDBC;
    static final int GATEWAY_PORT;
    static final int VALIDATION_PORT;
    static final int LEDGER_PORT;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static {
        POSTGRES.start();
        KAFKA.start();
        System.setProperty("LAB_PROFILE", PROFILE);
        ConfigurableApplicationContext gateway = start(PaymentGatewayApplication.class,
                PaymentGatewayApplication.CONFIG_NAME);
        ConfigurableApplicationContext validation = start(ValidationServiceApplication.class,
                ValidationServiceApplication.CONFIG_NAME);
        ConfigurableApplicationContext ledger = start(LedgerServiceApplication.class,
                LedgerServiceApplication.CONFIG_NAME);
        GATEWAY_PORT = port(gateway);
        VALIDATION_PORT = port(validation);
        LEDGER_PORT = port(ledger);
        JDBC = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private Lab() {
    }

    private static int port(ConfigurableApplicationContext ctx) {
        return Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port"));
    }

    static String scrape(int port) {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus"))
                .GET().build()).body();
    }

    private static ConfigurableApplicationContext start(Class<?> app, String configName) {
        return new SpringApplicationBuilder(app).properties(configName).run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers());
    }

    /** Seeds an account in both the validation and ledger schemas. Returns its id. */
    static String account(String clientId, long balanceMinor) {
        String id = "T-" + UUID.randomUUID().toString().substring(0, 12);
        JDBC.update("INSERT INTO validation.accounts(id, client_id, currency, status) VALUES (?,?,'USD','ACTIVE')",
                id, clientId);
        JDBC.update("INSERT INTO validation.account_limits(account_id, limit_type, amount_usd_minor) VALUES (?,'PER_TX',1000000000)", id);
        ledgerAccount(id, clientId, balanceMinor);
        return id;
    }

    static void ledgerAccount(String id, String clientId, long balanceMinor) {
        JDBC.update("""
                INSERT INTO ledger.accounts(id, client_id, currency, overdraft, balance_minor, daily_limit_minor)
                VALUES (?,?,'USD',false,?,1000000000000)""", id, clientId, balanceMinor);
    }

    static HttpResponse<String> post(String clientId, String key, String body) {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + GATEWAY_PORT + "/payments"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .header("X-Client-Id", clientId)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    static HttpResponse<String> get(String clientId, String paymentId) {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + GATEWAY_PORT + "/payments/" + paymentId))
                .header("X-Client-Id", clientId).GET().build());
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String body(String debtor, String creditor, String merchant, long amount) {
        return "{\"debtorAccountId\":\"%s\",\"creditorAccountId\":\"%s\",\"merchantId\":\"%s\",\"amountMinor\":%d,\"currency\":\"USD\"}"
                .formatted(debtor, creditor, merchant, amount);
    }

    static void produce(String topic, String key, String value) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.<String, Object>of(
                "bootstrap.servers", KAFKA.getBootstrapServers(),
                "key.serializer", "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer", "org.apache.kafka.common.serialization.StringSerializer"))) {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads a topic from the start and returns the record key of the first record containing the needle. */
    static String keyOf(String topic, String needle) {
        return recordWith(topic, needle).key();
    }

    static String valueOf(String topic, String needle) {
        return recordWith(topic, needle).value();
    }

    static void pauseKafka() {
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    static void unpauseKafka() {
        KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    private static ConsumerRecord<String, String> recordWith(String topic, String needle) {
        Properties props = new Properties();
        props.put("bootstrap.servers", KAFKA.getBootstrapServers());
        props.put("group.id", "lab-test-" + UUID.randomUUID());
        props.put("auto.offset.reset", "earliest");
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<org.apache.kafka.common.TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(p -> new org.apache.kafka.common.TopicPartition(topic, p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (r.value().contains(needle)) {
                        return r;
                    }
                }
            }
        }
        throw new AssertionError("no record containing " + needle + " on " + topic);
    }

    static int partitionCount(String topic) {
        try (Admin admin = Admin.create(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            return admin.describeTopics(List.of(topic)).allTopicNames().get().get(topic).partitions().size();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Total records ever written to a topic (sum of end offsets). */
    static long recordCount(String topic) {
        Properties props = new Properties();
        props.put("bootstrap.servers", KAFKA.getBootstrapServers());
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<org.apache.kafka.common.TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(p -> new org.apache.kafka.common.TopicPartition(topic, p.partition())).toList();
            return consumer.endOffsets(partitions).values().stream().mapToLong(Long::longValue).sum();
        }
    }

    /** Number of consumer threads (group members) currently in a consumer group. */
    static int groupMembers(String group) {
        try (Admin admin = Admin.create(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            return admin.describeConsumerGroups(List.of(group)).all().get().get(group).members().size();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean tuned() {
        return "tuned".equals(PROFILE);
    }

    static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    static int count(String sql, Object... args) {
        Integer n = JDBC.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}

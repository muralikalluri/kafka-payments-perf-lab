package lab.payments.e2e;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import lab.payments.ledgerservice.LedgerServiceApplication;
import lab.payments.notificationservice.NotificationServiceApplication;
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
    static final org.testcontainers.containers.GenericContainer<?> REDIS =
            new org.testcontainers.containers.GenericContainer<>("redis:7").withExposedPorts(6379);
    /** Opt-in (-Dlab.tracing=true): a Jaeger container the services export traces to, with sampling at 100%. */
    static final boolean TRACING = Boolean.getBoolean("lab.tracing");
    static final org.testcontainers.containers.GenericContainer<?> JAEGER =
            new org.testcontainers.containers.GenericContainer<>("jaegertracing/all-in-one:1.62.0")
                    .withExposedPorts(4318, 16686).withEnv("COLLECTOR_OTLP_ENABLED", "true")
                    .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/").forPort(16686));
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");
    static final JdbcTemplate JDBC;
    static final ConfigurableApplicationContext NOTIFICATION;
    static final int NOTIFICATION_PORT;
    static final ConfigurableApplicationContext GATEWAY;
    static final ConfigurableApplicationContext VALIDATION;
    static final ConfigurableApplicationContext LEDGER;
    static final int GATEWAY_PORT;
    static final int VALIDATION_PORT;
    static final int LEDGER_PORT;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static {
        POSTGRES.start();
        if (TRACING) {
            JAEGER.start(); // first: its image is large and a long pull after Kafka starts has broken the Kafka connection
        }
        KAFKA.start();
        if ("tuned".equals(PROFILE)) {
            REDIS.start();
        }
        System.setProperty("LAB_PROFILE", PROFILE);
        // Fast retries so a failing webhook reaches its final state within a test.
        NOTIFICATION = start(NotificationServiceApplication.class, NotificationServiceApplication.CONFIG_NAME,
                "--lab.notification.backoff-base-ms=50", "--lab.notification.max-attempts=3");
        NOTIFICATION_PORT = Integer.parseInt(NOTIFICATION.getEnvironment().getProperty("local.server.port"));
        GATEWAY = start(PaymentGatewayApplication.class,
                PaymentGatewayApplication.CONFIG_NAME,
                "--lab.notification.webhook-url=http://localhost:" + NOTIFICATION_PORT + "/webhooks/{clientId}");
        VALIDATION = start(ValidationServiceApplication.class,
                ValidationServiceApplication.CONFIG_NAME);
        LEDGER = start(LedgerServiceApplication.class,
                LedgerServiceApplication.CONFIG_NAME, "--lab.settlement.reconcile-ms=500");
        GATEWAY_PORT = port(GATEWAY);
        VALIDATION_PORT = port(VALIDATION);
        LEDGER_PORT = port(LEDGER);
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

    private static ConfigurableApplicationContext start(Class<?> app, String configName, String... extra) {
        List<String> args = new java.util.ArrayList<>(List.of(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers()));
        args.addAll(List.of(extra));
        if (TRACING) {
            args.add("--management.tracing.sampling.probability=1.0");
            args.add("--management.otlp.tracing.endpoint=http://localhost:" + JAEGER.getMappedPort(4318) + "/v1/traces");
        }
        if (REDIS.isRunning()) {
            args.add("--spring.data.redis.host=" + REDIS.getHost());
            args.add("--spring.data.redis.port=" + REDIS.getMappedPort(6379));
        }
        return new SpringApplicationBuilder(app).properties(configName).run(args.toArray(new String[0]));
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

    /** Stops the ledger's consumers from fetching so records queue up and later arrive as one batch. */
    static void pauseLedgerConsumers() {
        var containers = LEDGER.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class)
                .getListenerContainers();
        containers.forEach(org.springframework.kafka.listener.MessageListenerContainer::pause);
        await("ledger consumers paused", () -> containers.stream()
                .allMatch(org.springframework.kafka.listener.MessageListenerContainer::isContainerPaused));
    }

    static void resumeLedgerConsumers() {
        LEDGER.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class)
                .getListenerContainers().forEach(org.springframework.kafka.listener.MessageListenerContainer::resume);
    }

    static void pausePostgres() {
        POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
    }

    static void unpausePostgres() {
        POSTGRES.getDockerClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec();
    }

    static void pauseRedis() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    static void unpauseRedis() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }

    /** Waits until validation's per-instance cache-invalidation consumer group has an assigned member. */
    static void awaitInvalidationConsumer() {
        await("account.updated consumer assigned", () -> {
            try (Admin admin = Admin.create(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
                for (var group : admin.listConsumerGroups().all().get()) {
                    if (group.groupId().startsWith("validation-cache-")) {
                        return !admin.describeConsumerGroups(List.of(group.groupId())).all().get()
                                .get(group.groupId()).members().isEmpty();
                    }
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        });
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

    /** Postings per applied payment: debtor -> settlement -> creditor, four legs (F-11; settlement nets to zero). */
    static int postingsPerPayment() {
        return 4;
    }

    /** Changes the webhook simulator's behaviour; null leaves a setting as it is. */
    static void simulate(Long latencyMs, Double failureRate, Double failAfterAcceptRate) {
        String body = String.format(java.util.Locale.ROOT, "{\"latencyMs\":%s,\"failureRate\":%s,\"failAfterAcceptRate\":%s}",
                latencyMs, failureRate, failAfterAcceptRate);
        send(HttpRequest.newBuilder(URI.create("http://localhost:" + NOTIFICATION_PORT + "/sim/config"))
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    static void resetSimulator() {
        simulate(5L, 0.0, 0.0);
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

package edu.smarttraffic.kafkasecurity;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import javax.security.auth.login.LoginException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KafkaOAuthBrokerIT {
    private static final String STATIC_TOKEN_CALLBACK =
            ExternalJwtLoginCallbackHandler.class.getName();
    private static final String PRODUCER_CLIENT_ID = "KAFKA_OAUTH_CLIENT_ID";
    private static final String PRODUCER_CLIENT_SECRET = "KAFKA_OAUTH_CLIENT_SECRET";

    @Test
    void brokerAllowsOnlyValidIssuerAudienceAndAcl() throws Exception {
        Map<String, String> environment = new HashMap<>(System.getenv());
        String topic = required(environment, "KAFKA_IT_TOPIC");

        publish(KafkaOAuthClientConfiguration.fromEnvironment(environment).producerProperties(), topic);

        assertRejected(
                () -> staticTokenProperties(environment, null, true),
                topic,
                "A client without a token must not publish",
                List.of(AuthenticationException.class, LoginException.class));
        assertRejected(
                () -> staticTokenProperties(environment, requiredPath(environment, "KAFKA_IT_INVALID_JWT_FILE"), false),
                topic,
                "A malformed or untrusted token must not publish",
                List.of(AuthenticationException.class));
        assertRejected(
                () -> staticTokenProperties(
                        environment, requiredPath(environment, "KAFKA_IT_WRONG_ISSUER_JWT_FILE"), false),
                topic,
                "A token with the wrong issuer must not publish",
                List.of(AuthenticationException.class));
        assertRejected(
                () -> staticTokenProperties(
                        environment, requiredPath(environment, "KAFKA_IT_WRONG_AUDIENCE_JWT_FILE"), false),
                topic,
                "A token with the wrong audience must not publish",
                List.of(AuthenticationException.class));

        Map<String, String> aclDeniedEnvironment = new HashMap<>(environment);
        aclDeniedEnvironment.put(
                PRODUCER_CLIENT_ID, required(environment, "KAFKA_IT_ACL_DENIED_CLIENT_ID"));
        aclDeniedEnvironment.put(
                PRODUCER_CLIENT_SECRET, required(environment, "KAFKA_IT_ACL_DENIED_CLIENT_SECRET"));
        assertRejected(
                () -> KafkaOAuthClientConfiguration.fromEnvironment(aclDeniedEnvironment).producerProperties(),
                topic,
                "A valid principal without WRITE ACL must not publish",
                List.of(TopicAuthorizationException.class));
    }

    @Test
    @Order(1)
    void brokerReauthenticatesAnOpenConnection() throws Exception {
        Map<String, String> environment = new HashMap<>(System.getenv());
        String topic = required(environment, "KAFKA_IT_TOPIC");
        long waitMs = Long.parseLong(required(environment, "KAFKA_IT_REAUTH_WAIT_MS"));
        Path audit = Path.of(required(environment, "KAFKA_IT_TOKEN_AUDIT_FILE"));
        Path positiveMarker = Path.of(required(environment, "KAFKA_IT_POSITIVE_MARKER_FILE"));
        Files.deleteIfExists(audit);
        Files.deleteIfExists(positiveMarker);

        Properties properties = KafkaOAuthClientConfiguration.fromEnvironment(environment).producerProperties();
        setShortTestTimeouts(properties);
        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(properties, new StringSerializer(), new StringSerializer())) {
            String controlId = "reauth-positive-control-" + UUID.randomUUID();
            String acceptedId = "reauth-positive-accepted-" + UUID.randomUUID();
            send(producer, topic, controlId);
            Files.createFile(positiveMarker);
            double successfulBefore = metric(producer, "successful-reauthentication-total");
            Thread.sleep(waitMs);
            send(producer, topic, acceptedId);
            assertTrue(metric(producer, "successful-reauthentication-total") > successfulBefore);
            assertTopicContains(environment, topic, acceptedId);
        }
        copyAudit(audit, Path.of(required(environment, "KAFKA_IT_POSITIVE_AUDIT_FILE")));
    }

    @Test
    @Order(2)
    void brokerFailsClosedWhenReauthenticationCannotRenew() throws Exception {
        Map<String, String> environment = new HashMap<>(System.getenv());
        String topic = required(environment, "KAFKA_IT_TOPIC");
        long waitMs = Long.parseLong(required(environment, "KAFKA_IT_REAUTH_WAIT_MS"));
        Path marker = Path.of(required(environment, "KAFKA_IT_REAUTH_MARKER_FILE"));
        Path audit = Path.of(required(environment, "KAFKA_IT_TOKEN_AUDIT_FILE"));
        Files.deleteIfExists(marker);
        Files.deleteIfExists(audit);

        Properties properties = KafkaOAuthClientConfiguration.fromEnvironment(environment).producerProperties();
        setShortTestTimeouts(properties);
        Exception publicationFailure = null;
        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(properties, new StringSerializer(), new StringSerializer())) {
            String controlId = "reauth-control-" + UUID.randomUUID();
            String rejectedId = "reauth-rejected-" + UUID.randomUUID();
            send(producer, topic, controlId);
            Files.createFile(marker);

            double failedBefore = metric(producer, "failed-reauthentication-total");
            double closeBefore = metric(producer, "connection-close-total");
            double creationBefore = metric(producer, "connection-creation-total");
            Thread.sleep(waitMs);
            try {
                send(producer, topic, rejectedId);
            } catch (Exception exception) {
                publicationFailure = exception;
            }

            boolean rejectedMessageObserved = topicContains(environment, topic, rejectedId);

            assertTrue(metric(producer, "failed-reauthentication-total") > failedBefore);
            assertNotNull(publicationFailure);
            assertTrue(metric(producer, "connection-creation-total") >= creationBefore);
            assertTrue(metric(producer, "connection-close-total") > closeBefore);
            assertTrue(!rejectedMessageObserved);
        }
        copyAudit(audit, Path.of(required(environment, "KAFKA_IT_NEGATIVE_AUDIT_FILE")));
    }

    private static Properties staticTokenProperties(
            Map<String, String> environment, Path tokenFile, boolean tokenAbsent) {
        Properties properties = KafkaOAuthClientConfiguration.fromEnvironment(environment).producerProperties();
        properties.put("sasl.login.callback.handler.class", STATIC_TOKEN_CALLBACK);

        String jaas = OAuthBearerLoginModule.class.getName() + " required";
        if (tokenAbsent) {
            jaas += " token.absent=\"true\"";
        } else {
            jaas += " token.file=\"" + escapeJaas(tokenFile.toAbsolutePath().toString()) + "\"";
        }
        properties.put("sasl.jaas.config", jaas + ";");
        setShortTestTimeouts(properties);
        return properties;
    }

    private static void assertRejected(
            Supplier<Properties> properties,
            String topic,
            String assertionMessage,
            List<Class<? extends Throwable>> expectedCauses) {
        Exception failure = assertThrows(
                Exception.class,
                () -> publish(properties.get(), topic),
                assertionMessage);
        assertTrue(
                hasCause(failure, expectedCauses),
                () -> assertionMessage + "; received " + causeSummary(failure));
    }

    private static void publish(Properties properties, String topic)
            throws InterruptedException, ExecutionException, TimeoutException {
        setShortTestTimeouts(properties);
        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(properties, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(
                            topic,
                            UUID.randomUUID().toString(),
                            "kafka-oauth-integration-test"))
                    .get(20, TimeUnit.SECONDS);
        }
    }

    private static void send(
            KafkaProducer<String, String> producer, String topic, String value)
            throws InterruptedException, ExecutionException, TimeoutException {
        producer.send(new ProducerRecord<>(topic, UUID.randomUUID().toString(), value))
                .get(20, TimeUnit.SECONDS);
    }

    private static double metric(KafkaProducer<String, String> producer, String name) {
        return producer.metrics().entrySet().stream()
                .filter(entry -> entry.getKey().name().equals(name))
                .map(entry -> ((Number) entry.getValue().metricValue()).doubleValue())
                .findFirst()
                .orElse(0.0);
    }

    private static void assertTopicContains(
            Map<String, String> environment, String topic, String value) {
        assertTrue(topicContains(environment, topic, value), "Expected message was not found");
    }

    private static boolean topicContains(
            Map<String, String> environment, String topic, String value) {
        Map<String, String> adminEnvironment = new HashMap<>(environment);
        adminEnvironment.put(PRODUCER_CLIENT_ID, required(environment, "KAFKA_IT_ADMIN_CLIENT_ID"));
        adminEnvironment.put(PRODUCER_CLIENT_SECRET, required(environment, "KAFKA_IT_ADMIN_CLIENT_SECRET"));
        Properties consumerProperties = KafkaOAuthClientConfiguration.fromEnvironment(adminEnvironment)
                .consumerProperties("reauth-verification-" + UUID.randomUUID());
        consumerProperties.put("auto.offset.reset", "earliest");
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(consumerProperties, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(java.time.Duration.ofSeconds(1))) {
                    if (value.equals(record.value())) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static void copyAudit(Path source, Path destination) throws Exception {
        Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void setShortTestTimeouts(Properties properties) {
        properties.put("max.block.ms", "10000");
        properties.put("request.timeout.ms", "5000");
        properties.put("delivery.timeout.ms", "12000");
        properties.put("reconnect.backoff.max.ms", "1000");
    }

    private static boolean hasCause(
            Throwable failure, List<Class<? extends Throwable>> expectedCauses) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            for (Class<? extends Throwable> expectedCause : expectedCauses) {
                if (expectedCause.isInstance(cause)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String causeSummary(Throwable failure) {
        StringBuilder summary = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (summary.length() > 0) {
                summary.append(" -> ");
            }
            summary.append(cause.getClass().getSimpleName());
        }
        return summary.toString();
    }

    private static Path requiredPath(Map<String, String> environment, String name) {
        Path path = Path.of(required(environment, name)).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(path), () -> name + " must point to a readable JWT file outside Git");
        assertTrue(Files.isReadable(path), () -> name + " must point to a readable JWT file outside Git");
        return path;
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        assertNotNull(value, () -> name + " must be supplied externally");
        assertTrue(!value.isBlank(), () -> name + " must not be blank");
        return value.trim();
    }

    private static String escapeJaas(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

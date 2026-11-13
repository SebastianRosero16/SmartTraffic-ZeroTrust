package edu.smarttraffic.kafkasecurity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import org.junit.jupiter.api.BeforeAll;
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
    private static final String SUCCESSFUL_REAUTH_METRIC = "successful-reauthentication-total";
    private static final String FAILED_REAUTH_METRIC = "failed-reauthentication-total";
    private static final long EXPECTED_TOKEN_TTL_SECONDS = 300;
    private static final long EXPECTED_REAUTH_INTERVAL_MS = 240_000;
    private static final long MAX_CASE_WAIT_MS = Duration.ofMinutes(6).toMillis();
    private static final long POST_EXPIRY_GRACE_MS = Duration.ofSeconds(60).toMillis();
    private static final long POLL_INTERVAL_MS = 250;
    private static final long CONTROL_REQUEST_TIMEOUT_MS = Duration.ofSeconds(20).toMillis();
    private static final long SEND_TIMEOUT_MS = Duration.ofSeconds(20).toMillis();
    private static final long TOPIC_CHECK_TIMEOUT_MS = Duration.ofSeconds(10).toMillis();
    private static ReauthRun reauthRun;

    @BeforeAll
    static void prepareReauthenticationRun() throws IOException {
        reauthRun = ReauthRun.fromEnvironment(System.getenv());
    }

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
        String clientId = required(environment, PRODUCER_CLIENT_ID);
        long caseStartNanos = System.nanoTime();
        ReauthDeadline caseLimit = ReauthDeadline.caseLimit(caseStartNanos);
        AuditTrail audit = reauthRun.newAuditTrail("reauth-positive", clientId);
        String result = "failed";
        try {
            Properties properties = KafkaOAuthClientConfiguration.fromEnvironment(environment).producerProperties();
            setShortTestTimeouts(properties);
            KafkaProducer<String, String> producer =
                    new KafkaProducer<>(properties, new StringSerializer(), new StringSerializer());
            try {
                String controlId = "reauth-positive-control-" + UUID.randomUUID();
                String acceptedId = "reauth-positive-accepted-" + UUID.randomUUID();
                send(producer, topic, controlId, caseLimit.deadlineNanos());
                TokenAuditEvent initialToken = awaitTokenEvent(
                        reauthRun.tokenAuditPath,
                        clientId,
                        0,
                        200,
                        boundedDeadline(caseLimit.deadlineNanos(), CONTROL_REQUEST_TIMEOUT_MS));
                ReauthDeadline renewalDeadline = ReauthDeadline.forRenewal(
                        "positive",
                        caseStartNanos,
                        initialToken,
                        reauthRun.reauthIntervalMs);
                double successfulBefore = metric(producer, SUCCESSFUL_REAUTH_METRIC);
                audit.record(
                        "initial-token",
                        "status=200",
                        "iatEpochSeconds=" + initialToken.issuedAtEpochSeconds,
                        "expEpochSeconds=" + initialToken.expiresAtEpochSeconds,
                        "ttlSeconds=" + initialToken.ttlSeconds(),
                        "effectiveReauthIntervalMs=" + reauthRun.reauthIntervalMs);
                awaitReauthentication(
                        producer,
                        reauthRun.tokenAuditPath,
                        clientId,
                        initialToken.sequence,
                        200,
                        SUCCESSFUL_REAUTH_METRIC,
                        successfulBefore,
                        renewalDeadline,
                        audit);
                send(producer, topic, acceptedId, caseLimit.deadlineNanos());
                assertTopicContains(environment, topic, acceptedId, caseLimit.deadlineNanos());
                audit.record(
                        "post-renewal-send",
                        "status=acknowledged",
                        "successfulReauthTotal=" + metric(producer, SUCCESSFUL_REAUTH_METRIC));
            } finally {
                closeWithinDeadline(producer, caseLimit.deadlineNanos());
            }
            assertCaseWithinLimit(caseStartNanos, caseLimit.deadlineNanos());
            result = "passed";
        } catch (Exception | AssertionError failure) {
            audit.record("failure", "type=" + failure.getClass().getSimpleName());
            throw failure;
        } finally {
            audit.finish(result, caseStartNanos);
        }
    }

    @Test
    @Order(2)
    void brokerFailsClosedWhenReauthenticationCannotRenew() throws Exception {
        Map<String, String> environment = new HashMap<>(System.getenv());
        String topic = required(environment, "KAFKA_IT_TOPIC");
        String clientId = required(environment, PRODUCER_CLIENT_ID);
        long caseStartNanos = System.nanoTime();
        ReauthDeadline caseLimit = ReauthDeadline.caseLimit(caseStartNanos);
        AuditTrail audit = reauthRun.newAuditTrail("reauth-negative", clientId);
        String result = "failed";
        try {
            Properties properties = KafkaOAuthClientConfiguration.fromEnvironment(environment).producerProperties();
            setShortTestTimeouts(properties);
            KafkaProducer<String, String> producer =
                    new KafkaProducer<>(properties, new StringSerializer(), new StringSerializer());
            try {
                String controlId = "reauth-negative-control-" + UUID.randomUUID();
                String rejectedId = "reauth-negative-rejected-" + UUID.randomUUID();
                long sequenceBeforeInitial = latestSequence(reauthRun.tokenAuditPath);
                send(producer, topic, controlId, caseLimit.deadlineNanos());
                TokenAuditEvent initialToken = awaitTokenEvent(
                        reauthRun.tokenAuditPath,
                        clientId,
                        sequenceBeforeInitial,
                        200,
                        boundedDeadline(caseLimit.deadlineNanos(), CONTROL_REQUEST_TIMEOUT_MS));
                ReauthDeadline renewalDeadline = ReauthDeadline.forRenewal(
                        "negative",
                        caseStartNanos,
                        initialToken,
                        reauthRun.reauthIntervalMs);
                audit.record(
                        "initial-token",
                        "status=200",
                        "iatEpochSeconds=" + initialToken.issuedAtEpochSeconds,
                        "expEpochSeconds=" + initialToken.expiresAtEpochSeconds,
                        "ttlSeconds=" + initialToken.ttlSeconds(),
                        "effectiveReauthIntervalMs=" + reauthRun.reauthIntervalMs);

                double failedBefore = metric(producer, FAILED_REAUTH_METRIC);
                long sequenceBeforeRenewal = latestSequence(reauthRun.tokenAuditPath);
                Files.createFile(reauthRun.rejectionMarkerPath);
                audit.record("rejection-armed", "status=ready");
                awaitReauthentication(
                        producer,
                        reauthRun.tokenAuditPath,
                        clientId,
                        sequenceBeforeRenewal,
                        401,
                        FAILED_REAUTH_METRIC,
                        failedBefore,
                        renewalDeadline,
                        audit);

                Exception publicationFailure = null;
                try {
                    send(producer, topic, rejectedId, caseLimit.deadlineNanos());
                } catch (Exception exception) {
                    publicationFailure = exception;
                }

                assertNotNull(publicationFailure, "The post-renewal send must not be acknowledged");
                audit.record(
                        "post-renewal-send",
                        "status=failed",
                        "failureType=" + publicationFailure.getClass().getSimpleName());

                boolean rejectedMessageObserved = topicContains(
                        environment, topic, rejectedId, caseLimit.deadlineNanos());
                assertFalse(rejectedMessageObserved, "The post-renewal record must not appear in the topic");
                assertPostMarkerRequestsRejected(
                        reauthRun.tokenAuditPath,
                        clientId,
                        sequenceBeforeRenewal);
                audit.record(
                        "negative-assertions",
                        "failedReauthTotal=" + metric(producer, FAILED_REAUTH_METRIC),
                        "postRenewalRecordPresent=false");
            } finally {
                closeWithinDeadline(producer, caseLimit.deadlineNanos());
            }
            assertCaseWithinLimit(caseStartNanos, caseLimit.deadlineNanos());
            result = "passed";
        } catch (Exception | AssertionError failure) {
            audit.record("failure", "type=" + failure.getClass().getSimpleName());
            throw failure;
        } finally {
            audit.finish(result, caseStartNanos);
        }
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
            KafkaProducer<String, String> producer, String topic, String value, long deadlineNanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        producer.send(new ProducerRecord<>(topic, UUID.randomUUID().toString(), value))
                .get(remainingMillis(deadlineNanos, SEND_TIMEOUT_MS), TimeUnit.MILLISECONDS);
    }

    private static double metric(KafkaProducer<String, String> producer, String name) {
        List<Double> values = producer.metrics().entrySet().stream()
                .filter(entry -> entry.getKey().name().equals(name))
                .map(entry -> ((Number) entry.getValue().metricValue()).doubleValue())
                .toList();
        assertFalse(values.isEmpty(), "Kafka producer metric is unavailable: " + name);
        return values.stream().mapToDouble(Double::doubleValue).sum();
    }

    private static void assertTopicContains(
            Map<String, String> environment, String topic, String value, long deadlineNanos)
            throws TimeoutException {
        assertTrue(
                topicContains(environment, topic, value, deadlineNanos),
                "Expected message was not found");
    }

    private static boolean topicContains(
            Map<String, String> environment, String topic, String value, long deadlineNanos)
            throws TimeoutException {
        Map<String, String> adminEnvironment = new HashMap<>(environment);
        adminEnvironment.put(PRODUCER_CLIENT_ID, required(environment, "KAFKA_IT_ADMIN_CLIENT_ID"));
        adminEnvironment.put(PRODUCER_CLIENT_SECRET, required(environment, "KAFKA_IT_ADMIN_CLIENT_SECRET"));
        Properties consumerProperties = KafkaOAuthClientConfiguration.fromEnvironment(adminEnvironment)
                .consumerProperties("reauth-verification-" + UUID.randomUUID());
        consumerProperties.put("auto.offset.reset", "earliest");
        KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(consumerProperties, new StringDeserializer(), new StringDeserializer());
        try {
            consumer.subscribe(List.of(topic));
            long topicDeadlineNanos = Math.min(
                    deadlineNanos,
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOPIC_CHECK_TIMEOUT_MS));
            while (System.nanoTime() < topicDeadlineNanos) {
                long pollMs = remainingMillis(topicDeadlineNanos, TimeUnit.SECONDS.toMillis(1));
                for (ConsumerRecord<String, String> record :
                        consumer.poll(Duration.ofMillis(pollMs))) {
                    if (value.equals(record.value())) {
                        return true;
                    }
                }
            }
            return false;
        } finally {
            closeWithinDeadline(consumer, deadlineNanos);
        }
    }

    private static TokenAuditEvent awaitTokenEvent(
            Path auditPath,
            String clientId,
            long afterSequence,
            int expectedStatus,
            long deadlineNanos) throws Exception {
        long startedNanos = System.nanoTime();
        long effectiveTimeoutMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - startedNanos);
        while (System.nanoTime() < deadlineNanos) {
            for (TokenAuditEvent event : readTokenAudit(auditPath)) {
                if (event.sequence > afterSequence && event.clientId.equals(clientId)) {
                    assertEquals(
                            expectedStatus,
                            event.status,
                            "Unexpected token endpoint status for " + clientId);
                    return event;
                }
            }
            pollDelay(deadlineNanos);
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        throw new AssertionError(
                "Timed out waiting for token endpoint HTTP " + expectedStatus
                        + "; effectiveTimeoutMs=" + effectiveTimeoutMs
                        + "; elapsedMs=" + elapsedMs);
    }

    private static void awaitReauthentication(
            KafkaProducer<String, String> producer,
            Path tokenAuditPath,
            String clientId,
            long afterSequence,
            int expectedStatus,
            String metricName,
            double metricBefore,
            ReauthDeadline deadline,
            AuditTrail audit) throws Exception {
        long startedNanos = System.nanoTime();
        long effectiveTimeoutMs = deadline.remainingMs();
        logWaitStart(deadline.caseName, effectiveTimeoutMs, deadline.deadlineEpochMs);
        audit.record(
                "reauth-wait-started",
                "effectiveTimeoutMs=" + effectiveTimeoutMs,
                "deadlineEpochMs=" + deadline.deadlineEpochMs,
                "metric=" + metricName,
                "metricBaseline=" + metricBefore);

        while (System.nanoTime() < deadline.deadlineNanos) {
            List<TokenAuditEvent> renewalEvents = readTokenAudit(tokenAuditPath).stream()
                    .filter(event -> event.sequence > afterSequence && event.clientId.equals(clientId))
                    .toList();
            for (TokenAuditEvent event : renewalEvents) {
                assertEquals(
                        expectedStatus,
                        event.status,
                        "Unexpected token endpoint status during reauthentication");
            }

            double observedMetric = metric(producer, metricName);
            if (!renewalEvents.isEmpty() && observedMetric > metricBefore) {
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
                audit.record(
                        "reauthentication-observed",
                        "httpStatus=" + expectedStatus,
                        "metric=" + metricName,
                        "metricValue=" + observedMetric,
                        "elapsedMs=" + elapsedMs);
                System.out.printf(
                        "kafka-it reauth case=%s state=observed effectiveTimeoutMs=%d elapsedMs=%d "
                                + "httpStatus=%d metric=%s metricValue=%.0f%n",
                        deadline.caseName,
                        effectiveTimeoutMs,
                        elapsedMs,
                        expectedStatus,
                        metricName,
                        observedMetric);
                return;
            }
            pollDelay(deadline.deadlineNanos);
        }

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        double observedMetric = metric(producer, metricName);
        audit.record(
                "reauthentication-timeout",
                "effectiveTimeoutMs=" + effectiveTimeoutMs,
                "elapsedMs=" + elapsedMs,
                "metric=" + metricName,
                "metricValue=" + observedMetric);
        throw new AssertionError(
                "Timed out waiting for HTTP " + expectedStatus + " and " + metricName
                        + "; effectiveTimeoutMs=" + effectiveTimeoutMs
                        + "; elapsedMs=" + elapsedMs
                        + "; metricBaseline=" + metricBefore
                        + "; metricValue=" + observedMetric);
    }

    private static void assertPostMarkerRequestsRejected(
            Path auditPath, String clientId, long afterSequence) throws IOException {
        List<TokenAuditEvent> events = readTokenAudit(auditPath).stream()
                .filter(event -> event.sequence > afterSequence && event.clientId.equals(clientId))
                .toList();
        assertFalse(events.isEmpty(), "Expected an audited token renewal after the rejection marker");
        assertTrue(
                events.stream().allMatch(event -> event.status == 401),
                "Every application token request after the marker must receive HTTP 401");
    }

    private static List<TokenAuditEvent> readTokenAudit(Path path) throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }
        String content = Files.readString(path, StandardCharsets.UTF_8);
        int completeLineEnd = content.lastIndexOf('\n');
        if (completeLineEnd < 0) {
            return List.of();
        }

        List<TokenAuditEvent> events = new ArrayList<>();
        for (String line : content.substring(0, completeLineEnd).split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            for (String field : line.split("\t")) {
                int separator = field.indexOf('=');
                if (separator > 0) {
                    fields.put(field.substring(0, separator), field.substring(separator + 1));
                }
            }
            events.add(new TokenAuditEvent(
                    Long.parseLong(required(fields, "sequence")),
                    required(fields, "clientId"),
                    Integer.parseInt(required(fields, "status")),
                    optionalLong(fields, "iatEpochSeconds"),
                    optionalLong(fields, "expEpochSeconds")));
        }
        return events;
    }

    private static long latestSequence(Path path) throws IOException {
        return readTokenAudit(path).stream()
                .mapToLong(event -> event.sequence)
                .max()
                .orElse(0);
    }

    private static long optionalLong(Map<String, String> fields, String name) {
        String value = fields.get(name);
        return value == null ? -1 : Long.parseLong(value);
    }

    private static void pollDelay(long deadlineNanos) throws InterruptedException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos > 0) {
            TimeUnit.NANOSECONDS.sleep(Math.min(
                    remainingNanos,
                    TimeUnit.MILLISECONDS.toNanos(POLL_INTERVAL_MS)));
        }
    }

    private static long remainingMillis(long deadlineNanos, long operationLimitMs)
            throws TimeoutException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new TimeoutException("Kafka integration case deadline elapsed");
        }
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        if (remainingMs <= 0) {
            throw new TimeoutException("Kafka integration case deadline elapsed");
        }
        return Math.min(operationLimitMs, remainingMs);
    }

    private static void closeWithinDeadline(
            KafkaProducer<String, String> producer, long deadlineNanos) {
        producer.close(Duration.ofMillis(nonNegativeRemainingMillis(deadlineNanos, 5_000)));
    }

    private static void closeWithinDeadline(
            KafkaConsumer<String, String> consumer, long deadlineNanos) {
        consumer.close(Duration.ofMillis(nonNegativeRemainingMillis(deadlineNanos, 5_000)));
    }

    private static long nonNegativeRemainingMillis(long deadlineNanos, long operationLimitMs) {
        return Math.max(
                0,
                Math.min(
                        operationLimitMs,
                        TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())));
    }

    private static void assertCaseWithinLimit(long caseStartNanos) {
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - caseStartNanos);
        assertTrue(
                elapsedMs <= MAX_CASE_WAIT_MS,
                "Reauthentication case exceeded its 6-minute bound; elapsedMs=" + elapsedMs);
    }

    private static void logWaitStart(String caseName, long timeoutMs, long deadlineEpochMs) {
        System.out.printf(
                "kafka-it reauth case=%s state=waiting effectiveTimeoutMs=%d deadlineEpochMs=%d%n",
                caseName,
                timeoutMs,
                deadlineEpochMs);
    }

    private static long boundedDeadline(long deadlineNanos, long operationLimitMs) {
        return Math.min(
                deadlineNanos,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(operationLimitMs));
    }

    private static void assertCaseWithinLimit(long caseStartNanos, long deadlineNanos) {
        assertTrue(
                System.nanoTime() <= deadlineNanos,
                "Reauthentication case exceeded its 6-minute monotonic deadline");
        assertCaseWithinLimit(caseStartNanos);
    }

    private static Path repositoryRoot() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toRealPath();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IOException("Could not locate the Git repository root for integration audit validation");
    }

    private static Path requiredExternalRunPath(
            Map<String, String> environment,
            String variable,
            Path runDirectory,
            Path repositoryRoot) throws IOException {
        Path path = Path.of(required(environment, variable)).toAbsolutePath().normalize();
        assertEquals(
                runDirectory,
                path.getParent().toRealPath(),
                variable + " must be a file in the unique audit run directory");
        assertFalse(
                path.startsWith(repositoryRoot),
                variable + " must be outside the Git repository");
        return path;
    }

    private static final class ReauthRun {
        private final String runId;
        private final Path runDirectory;
        private final Path tokenAuditPath;
        private final Path rejectionMarkerPath;
        private final long reauthIntervalMs;

        private ReauthRun(
                String runId,
                Path runDirectory,
                Path tokenAuditPath,
                Path rejectionMarkerPath,
                long reauthIntervalMs) {
            this.runId = runId;
            this.runDirectory = runDirectory;
            this.tokenAuditPath = tokenAuditPath;
            this.rejectionMarkerPath = rejectionMarkerPath;
            this.reauthIntervalMs = reauthIntervalMs;
        }

        private static ReauthRun fromEnvironment(Map<String, String> environment) throws IOException {
            String runId = required(environment, "KAFKA_IT_RUN_ID");
            assertTrue(
                    runId.matches("[A-Za-z0-9_-]{1,64}"),
                    "KAFKA_IT_RUN_ID must be a unique, path-safe identifier");

            Path repositoryRoot = repositoryRoot();
            Path configuredAuditRoot = Path.of(required(environment, "KAFKA_IT_AUDIT_DIR"));
            assertTrue(configuredAuditRoot.isAbsolute(), "KAFKA_IT_AUDIT_DIR must be absolute");
            Path auditRoot = configuredAuditRoot.toAbsolutePath().normalize();
            assertFalse(
                    auditRoot.startsWith(repositoryRoot),
                    "KAFKA_IT_AUDIT_DIR must be outside the Git repository");
            Files.createDirectories(auditRoot);
            Path realAuditRoot = auditRoot.toRealPath();
            assertFalse(realAuditRoot.startsWith(repositoryRoot), "KAFKA_IT_AUDIT_DIR must be outside Git");

            Path runDirectory = realAuditRoot.resolve(runId);
            Files.createDirectories(runDirectory);
            Path realRunDirectory = runDirectory.toRealPath();
            assertFalse(realRunDirectory.startsWith(repositoryRoot), "Reauthentication artifacts must be outside Git");

            Path tokenAuditPath = requiredExternalRunPath(
                    environment, "KAFKA_IT_TOKEN_AUDIT_FILE", realRunDirectory, repositoryRoot);
            Path rejectionMarkerPath = requiredExternalRunPath(
                    environment, "KAFKA_IT_REAUTH_MARKER_FILE", realRunDirectory, repositoryRoot);
            assertFalse(Files.exists(tokenAuditPath), "Token audit file already exists; use a new run ID");
            assertFalse(Files.exists(rejectionMarkerPath), "Rejection marker already exists; use a new run ID");
            assertFalse(
                    Files.exists(realRunDirectory.resolve("reauth-positive.tsv")),
                    "Positive audit already exists; use a new run ID");
            assertFalse(
                    Files.exists(realRunDirectory.resolve("reauth-negative.tsv")),
                    "Negative audit already exists; use a new run ID");

            long reauthIntervalMs;
            try {
                reauthIntervalMs = Long.parseLong(required(environment, "KAFKA_IT_REAUTH_INTERVAL_MS"));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("KAFKA_IT_REAUTH_INTERVAL_MS must be an integer", exception);
            }
            assertTrue(reauthIntervalMs > 0, "KAFKA_IT_REAUTH_INTERVAL_MS must be positive");
            assertEquals(
                    EXPECTED_REAUTH_INTERVAL_MS,
                    reauthIntervalMs,
                    "The effective local reauthentication interval must match the coordinated 240000 ms value");
            return new ReauthRun(
                    runId,
                    realRunDirectory,
                    tokenAuditPath,
                    rejectionMarkerPath,
                    reauthIntervalMs);
        }

        private AuditTrail newAuditTrail(String caseName, String clientId) throws IOException {
            return new AuditTrail(
                    runDirectory.resolve(caseName + ".tsv"),
                    runId,
                    caseName,
                    clientId);
        }
    }

    private static final class AuditTrail {
        private final Path path;
        private final String runId;
        private final String caseName;
        private final String clientId;

        private AuditTrail(Path path, String runId, String caseName, String clientId) throws IOException {
            this.path = path;
            this.runId = runId;
            this.caseName = caseName;
            this.clientId = clientId;
            Files.writeString(
                    path,
                    "",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            record("started", "status=running");
        }

        private void record(String event, String... fields) throws IOException {
            StringBuilder line = new StringBuilder()
                    .append("time=").append(Instant.now())
                    .append('\t').append("runId=").append(runId)
                    .append('\t').append("case=").append(caseName)
                    .append('\t').append("clientId=").append(safeAuditField(clientId))
                    .append('\t').append("event=").append(safeAuditField(event));
            for (String field : fields) {
                line.append('\t').append(safeAuditField(field));
            }
            Files.writeString(
                    path,
                    line.append(System.lineSeparator()).toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        }

        private void finish(String result, long caseStartNanos) throws IOException {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - caseStartNanos);
            record("finished", "status=" + result, "elapsedMs=" + elapsedMs);
            System.out.printf(
                    "kafka-it reauth case=%s state=%s elapsedMs=%d%n",
                    caseName,
                    result,
                    elapsedMs);
        }
    }

    private static String safeAuditField(String value) {
        return value.replace('\t', '_').replace('\r', '_').replace('\n', '_');
    }

    private record TokenAuditEvent(
            long sequence,
            String clientId,
            int status,
            long issuedAtEpochSeconds,
            long expiresAtEpochSeconds) {
        private long ttlSeconds() {
            return expiresAtEpochSeconds - issuedAtEpochSeconds;
        }
    }

    private static final class ReauthDeadline {
        private final String caseName;
        private final long deadlineNanos;
        private final long deadlineEpochMs;

        private ReauthDeadline(String caseName, long deadlineNanos, long deadlineEpochMs) {
            this.caseName = caseName;
            this.deadlineNanos = deadlineNanos;
            this.deadlineEpochMs = deadlineEpochMs;
        }

        private static ReauthDeadline caseLimit(long caseStartNanos) {
            long deadlineNanos = caseStartNanos + TimeUnit.MILLISECONDS.toNanos(MAX_CASE_WAIT_MS);
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            return new ReauthDeadline(
                    "case",
                    deadlineNanos,
                    System.currentTimeMillis() + Math.max(0, remainingMs));
        }

        private static ReauthDeadline forRenewal(
                String caseName,
                long caseStartNanos,
                TokenAuditEvent initialToken,
                long reauthIntervalMs) {
            assertEquals(
                    EXPECTED_TOKEN_TTL_SECONDS,
                    initialToken.ttlSeconds(),
                    "The observed access-token TTL must be 300 seconds for this integration run");
            assertTrue(
                    reauthIntervalMs < TimeUnit.SECONDS.toMillis(initialToken.ttlSeconds()),
                    "The effective reauthentication interval must be less than the observed token TTL");

            long reauthDeadlineEpochMs = TimeUnit.SECONDS.toMillis(initialToken.issuedAtEpochSeconds)
                    + reauthIntervalMs
                    + POST_EXPIRY_GRACE_MS;
            long expiryDeadlineEpochMs = TimeUnit.SECONDS.toMillis(initialToken.expiresAtEpochSeconds)
                    + POST_EXPIRY_GRACE_MS;
            long deadlineEpochMs = Math.min(reauthDeadlineEpochMs, expiryDeadlineEpochMs);
            long nowNanos = System.nanoTime();
            long remainingMs = Math.max(0, deadlineEpochMs - System.currentTimeMillis());
            long tokenDeadlineNanos =
                    nowNanos + TimeUnit.MILLISECONDS.toNanos(remainingMs);
            long caseDeadlineNanos =
                    caseStartNanos + TimeUnit.MILLISECONDS.toNanos(MAX_CASE_WAIT_MS);
            long deadlineNanos = Math.min(tokenDeadlineNanos, caseDeadlineNanos);
            assertTrue(
                    deadlineNanos > nowNanos,
                    "Reauthentication deadline has already elapsed; inspect token exp and effective broker interval");
            return new ReauthDeadline(caseName, deadlineNanos, deadlineEpochMs);
        }

        private long remainingMs() {
            return Math.max(
                    0,
                    TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
        }

        private long deadlineNanos() {
            return deadlineNanos;
        }
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

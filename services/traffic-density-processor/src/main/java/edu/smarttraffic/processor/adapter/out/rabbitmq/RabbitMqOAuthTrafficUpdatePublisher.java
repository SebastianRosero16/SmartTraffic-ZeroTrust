package edu.smarttraffic.processor.adapter.out.rabbitmq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import edu.smarttraffic.contracts.TrafficUpdate;
import edu.smarttraffic.kafkaserdes.JsonSerde;
import edu.smarttraffic.processor.application.port.out.TrafficUpdatePublisher;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public final class RabbitMqOAuthTrafficUpdatePublisher implements TrafficUpdatePublisher, AutoCloseable {
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration TOKEN_REFRESH_SKEW = Duration.ofSeconds(15);
    private static final long PUBLISH_CONFIRM_TIMEOUT_MILLIS = 5_000;

    private final Configuration configuration;
    private final HttpClient tokenHttpClient;
    private final SSLContext brokerSslContext;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonSerde<TrafficUpdate> trafficUpdateSerde = new JsonSerde<>(TrafficUpdate.class);
    private final AtomicReference<String> returnedMessage = new AtomicReference<>();

    private Connection connection;
    private Channel channel;
    private String accessToken;
    private Instant tokenRefreshAt = Instant.MIN;

    public RabbitMqOAuthTrafficUpdatePublisher(Map<String, String> environment) {
        configuration = Configuration.from(environment);
        tokenHttpClient = HttpClient.newBuilder()
                .sslContext(loadTrustContext(configuration.authCaPath()))
                .connectTimeout(HTTP_CONNECT_TIMEOUT)
                .build();
        brokerSslContext = loadTrustContext(configuration.brokerCaPath());
    }

    public static RabbitMqOAuthTrafficUpdatePublisher fromEnvironment() {
        return new RabbitMqOAuthTrafficUpdatePublisher(System.getenv());
    }

    @Override
    public synchronized void publish(TrafficUpdate update) {
        Objects.requireNonNull(update, "Traffic update is required");

        try {
            ensureConnection();
            byte[] body = trafficUpdateSerde.serializer().serialize(configuration.exchange(), update);
            AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .deliveryMode(2)
                    .build();
            returnedMessage.set(null);
            channel.basicPublish(
                    configuration.exchange(),
                    configuration.routingKey(),
                    true,
                    properties,
                    body);
            channel.waitForConfirmsOrDie(PUBLISH_CONFIRM_TIMEOUT_MILLIS);
            String returnDetails = returnedMessage.getAndSet(null);
            if (returnDetails != null) {
                throw new IllegalStateException(
                        "RabbitMQ returned traffic update as unroutable: " + returnDetails);
            }
        } catch (IOException | TimeoutException exception) {
            throw new IllegalStateException("Could not publish traffic update to RabbitMQ", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing traffic update to RabbitMQ", exception);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        IOException closeFailure = null;
        Channel activeChannel = channel;
        channel = null;
        if (activeChannel != null && activeChannel.isOpen()) {
            try {
                activeChannel.close();
            } catch (IOException exception) {
                closeFailure = exception;
            } catch (TimeoutException exception) {
                closeFailure = new IOException("Timed out while closing RabbitMQ channel", exception);
            }
        }

        Connection activeConnection = connection;
        connection = null;
        accessToken = null;
        tokenRefreshAt = Instant.MIN;
        if (activeConnection != null && activeConnection.isOpen()) {
            try {
                activeConnection.close();
            } catch (IOException exception) {
                if (closeFailure == null) {
                    closeFailure = exception;
                } else {
                    closeFailure.addSuppressed(exception);
                }
            }
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private void ensureConnection() throws IOException, InterruptedException, TimeoutException {
        Instant now = Instant.now();
        if (connection != null
                && connection.isOpen()
                && channel != null
                && channel.isOpen()
                && now.isBefore(tokenRefreshAt)) {
            return;
        }

        closeActiveConnection();
        returnedMessage.set(null);
        now = Instant.now();
        if (accessToken == null || !now.isBefore(tokenRefreshAt)) {
            AccessToken token = requestAccessToken();
            accessToken = token.value();
            tokenRefreshAt = token.refreshAt();
        }

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(configuration.host());
        factory.setPort(configuration.port());
        factory.setVirtualHost(configuration.virtualHost());
        factory.setUsername(configuration.clientId());
        factory.setPassword(accessToken);
        factory.setConnectionTimeout((int) HTTP_REQUEST_TIMEOUT.toMillis());
        factory.setHandshakeTimeout((int) HTTP_REQUEST_TIMEOUT.toMillis());
        factory.setRequestedHeartbeat(30);
        factory.setAutomaticRecoveryEnabled(false);
        factory.setTopologyRecoveryEnabled(false);
        factory.useSslProtocol(brokerSslContext);
        factory.enableHostnameVerification();

        Connection newConnection = factory.newConnection("traffic-density-processor");
        try {
            Channel newChannel = newConnection.createChannel();
            newChannel.addReturnListener((replyCode, replyText, exchange, routingKey, properties, body) ->
                    returnedMessage.set("reply code " + replyCode + " (" + replyText
                            + "), exchange " + exchange + ", routing key " + routingKey));
            newChannel.confirmSelect();
            connection = newConnection;
            channel = newChannel;
        } catch (IOException exception) {
            try {
                newConnection.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    private void closeActiveConnection() throws IOException {
        Channel activeChannel = channel;
        channel = null;
        if (activeChannel != null && activeChannel.isOpen()) {
            try {
                activeChannel.close();
            } catch (TimeoutException exception) {
                throw new IOException("Timed out while closing RabbitMQ channel", exception);
            }
        }

        Connection activeConnection = connection;
        connection = null;
        if (activeConnection != null && activeConnection.isOpen()) {
            activeConnection.close();
        }
    }

    private AccessToken requestAccessToken() throws IOException, InterruptedException {
        String basicCredentials = Base64.getEncoder().encodeToString(
                (configuration.clientId() + ":" + configuration.clientSecret())
                        .getBytes(StandardCharsets.UTF_8));
        String form = "grant_type=client_credentials&scope="
                + URLEncoder.encode(configuration.scope(), StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(configuration.tokenEndpoint())
                .timeout(HTTP_REQUEST_TIMEOUT)
                .header("Authorization", "Basic " + basicCredentials)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = tokenHttpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("OAuth token endpoint returned HTTP " + response.statusCode());
        }

        JsonNode tokenResponse = objectMapper.readTree(response.body());
        JsonNode tokenValue = tokenResponse.path("access_token");
        JsonNode expiresInValue = tokenResponse.path("expires_in");
        if (!tokenValue.isTextual() || tokenValue.asText().isBlank()) {
            throw new IOException("OAuth token endpoint returned no access token");
        }
        if (!expiresInValue.canConvertToLong() || expiresInValue.asLong() <= 0) {
            throw new IOException("OAuth token endpoint returned an invalid expires_in value");
        }

        long expiresInSeconds = expiresInValue.asLong();
        Instant expiresAt;
        try {
            expiresAt = Instant.now().plusSeconds(expiresInSeconds);
        } catch (DateTimeException | ArithmeticException exception) {
            throw new IOException("OAuth token expiry is outside the supported time range", exception);
        }
        Duration refreshSkew = TOKEN_REFRESH_SKEW.compareTo(Duration.ofSeconds(expiresInSeconds)) >= 0
                ? Duration.ZERO
                : TOKEN_REFRESH_SKEW;
        return new AccessToken(tokenValue.asText(), expiresAt.minus(refreshSkew));
    }

    private static SSLContext loadTrustContext(Path caCertificatePath) {
        try (var input = Files.newInputStream(caCertificatePath)) {
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(input);
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null);
            trustStore.setCertificateEntry("smarttraffic-trusted-ca", certificate);
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagerFactory.getTrustManagers(), null);
            return context;
        } catch (IOException | java.security.GeneralSecurityException exception) {
            throw new IllegalArgumentException(
                    "Unable to initialize TLS trust from " + caCertificatePath,
                    exception);
        }
    }

    private record AccessToken(String value, Instant refreshAt) {
    }

    private record Configuration(
            String host,
            int port,
            String virtualHost,
            String exchange,
            String routingKey,
            URI tokenEndpoint,
            String clientId,
            String clientSecret,
            String scope,
            Path authCaPath,
            Path brokerCaPath) {

        private static Configuration from(Map<String, String> environment) {
            Objects.requireNonNull(environment, "Environment is required");
            String host = required(environment, "RABBITMQ_HOST");
            int port = parsePort(required(environment, "RABBITMQ_PORT"));
            String virtualHost = required(environment, "RABBITMQ_VHOST");
            String exchange = required(environment, "RABBITMQ_EXCHANGE");
            String routingKey = optional(environment, "RABBITMQ_ROUTING_KEY");
            URI tokenEndpoint = httpsUri(required(environment, "AUTH_TOKEN_ENDPOINT_URI"));
            String clientId = required(environment, "RABBITMQ_OAUTH_CLIENT_ID");
            String clientSecret = required(environment, "RABBITMQ_OAUTH_CLIENT_SECRET");
            String scope = required(environment, "RABBITMQ_OAUTH_SCOPE");
            Path authCaPath = readableCertificate(environment, "AUTH_TLS_CA_CERT_PATH");
            Path brokerCaPath = readableCertificate(environment, "RABBITMQ_TLS_CA_CERT_PATH");
            return new Configuration(
                    host,
                    port,
                    virtualHost,
                    exchange,
                    routingKey,
                    tokenEndpoint,
                    clientId,
                    clientSecret,
                    scope,
                    authCaPath,
                    brokerCaPath);
        }

        private static String required(Map<String, String> environment, String name) {
            String value = environment.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value.trim();
        }

        private static String optional(Map<String, String> environment, String name) {
            String value = environment.get(name);
            return value == null ? "" : value.trim();
        }

        private static int parsePort(String value) {
            try {
                int port = Integer.parseInt(value);
                if (port < 1 || port > 65_535) {
                    throw new IllegalArgumentException("RABBITMQ_PORT must be between 1 and 65535");
                }
                return port;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("RABBITMQ_PORT must be an integer", exception);
            }
        }

        private static URI httpsUri(String value) {
            try {
                URI uri = URI.create(value);
                if (!"https".equalsIgnoreCase(uri.getScheme())
                        || uri.getHost() == null
                        || uri.getUserInfo() != null) {
                    throw new IllegalArgumentException(
                            "AUTH_TOKEN_ENDPOINT_URI must be an HTTPS URL without user info");
                }
                return uri;
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("AUTH_TOKEN_ENDPOINT_URI must be a valid HTTPS URL", exception);
            }
        }

        private static Path readableCertificate(Map<String, String> environment, String name) {
            Path path;
            try {
                path = Path.of(required(environment, name));
            } catch (InvalidPathException exception) {
                throw new IllegalArgumentException(name + " must be a valid file path", exception);
            }
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw new IllegalArgumentException(name + " must reference a readable CA certificate file");
            }
            return path;
        }
    }
}

package edu.smarttraffic.processor.adapter.out.rabbitmq;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "TRAFFIC_FLOW_IT", matches = "true")
class TrafficUpdateEndToEndIntegrationTest {
    private static final String READ_SCOPE =
            "smarttraffic-rabbitmq-it.read:smarttraffic-it/dashboard-updates-it";
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(45);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void consumesUpdateForReadingPublishedThroughKafkaStreams() throws Exception {
        String expectedZoneId = requiredEnvironment("TRAFFIC_FLOW_IT_EXPECTED_ZONE_ID");
        String readerSecret = requiredEnvironment("RABBITMQ_IT_READER_SECRET");
        Path caCertificatePath = Path.of(requiredEnvironment("TRAFFIC_FLOW_IT_CA_CERT"));
        SSLContext sslContext = loadTrustContext(caCertificatePath);
        String accessToken = requestReaderToken(readerSecret, sslContext);

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(environmentOrDefault("RABBITMQ_HOST", "rabbitmq"));
        factory.setPort(Integer.parseInt(environmentOrDefault("RABBITMQ_PORT", "5671")));
        factory.setVirtualHost(environmentOrDefault("RABBITMQ_VHOST", "smarttraffic-it"));
        factory.setUsername("ignored-oauth-username");
        factory.setPassword(accessToken);
        factory.setConnectionTimeout(5_000);
        factory.setHandshakeTimeout(5_000);
        factory.useSslProtocol(sslContext);
        factory.enableHostnameVerification();

        try (Connection connection = factory.newConnection("traffic-flow-it-reader");
                Channel channel = connection.createChannel()) {
            long deadline = System.nanoTime() + TEST_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                var message = channel.basicGet(
                        environmentOrDefault("TRAFFIC_FLOW_IT_QUEUE", "dashboard-updates-it"),
                        true);
                if (message == null) {
                    Thread.sleep(200);
                    continue;
                }

                JsonNode update = OBJECT_MAPPER.readTree(message.getBody());
                if (expectedZoneId.equals(update.path("zoneId").path("value").asText())) {
                    assertNotNull(update.get("measuredAt"));
                    assertNotNull(update.get("metrics"));
                    assertNotNull(update.get("status"));
                    return;
                }
            }
        }

        fail("No RabbitMQ TrafficUpdate was received for zone " + expectedZoneId);
    }

    private static String requestReaderToken(String clientSecret, SSLContext sslContext)
            throws Exception {
        String credentials = Base64.getEncoder().encodeToString(
                ("rabbit-reader-it:" + clientSecret).getBytes(StandardCharsets.UTF_8));
        String form = "grant_type=client_credentials&scope="
                + URLEncoder.encode(READ_SCOPE, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(environmentOrDefault(
                        "AUTH_TOKEN_ENDPOINT_URI",
                        "https://host.docker.internal:8443/oauth2/token")))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Test Auth Server token request failed with HTTP " + response.statusCode());
        }
        JsonNode tokenValue = OBJECT_MAPPER.readTree(response.body()).path("access_token");
        if (!tokenValue.isTextual() || tokenValue.asText().isBlank()) {
            throw new IllegalStateException("Test Auth Server returned no access token");
        }
        return tokenValue.asText();
    }

    private static SSLContext loadTrustContext(Path caCertificatePath) throws Exception {
        if (!Files.isRegularFile(caCertificatePath) || !Files.isReadable(caCertificatePath)) {
            throw new IllegalArgumentException("The test CA certificate must be readable");
        }
        X509Certificate certificate;
        try (var input = Files.newInputStream(caCertificatePath)) {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(input);
        }
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null);
        trustStore.setCertificateEntry("traffic-flow-it-ca", certificate);
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
        return sslContext;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required integration-test variable is missing: " + name);
        }
        return value;
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}

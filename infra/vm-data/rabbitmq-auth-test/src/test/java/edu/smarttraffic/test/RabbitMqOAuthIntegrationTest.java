package edu.smarttraffic.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "RABBITMQ_IT", matches = "true")
class RabbitMqOAuthIntegrationTest {
    private static final String PUBLISHER_CLIENT_ID = "rabbit-publisher-it";
    private static final String READER_CLIENT_ID = "rabbit-reader-it";
    private static final String RESOURCE_SERVER_ID = "smarttraffic-rabbitmq-it";
    private static final String VHOST = "smarttraffic-it";
    private static final String TRAFFIC_EXCHANGE = "traffic-updates-it";
    private static final String DASHBOARD_QUEUE = "dashboard-updates-it";
    private static final String PUBLISH_SCOPE =
            RESOURCE_SERVER_ID + ".write:" + VHOST + "/" + TRAFFIC_EXCHANGE;
    private static final String READ_SCOPE =
            RESOURCE_SERVER_ID + ".read:" + VHOST + "/" + DASHBOARD_QUEUE;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = createHttpClient();
    private static final PrivateKey SIGNING_KEY = loadPrivateKey();
    private static final KeyPair UNTRUSTED_KEY = generateRsaKeyPair();
    private static final String AUTH_BASE_URL =
            environmentOrDefault("RABBITMQ_IT_AUTH_BASE_URL", "https://host.docker.internal:8443");
    private static final String RABBITMQ_HOST =
            environmentOrDefault("RABBITMQ_IT_HOST", "rabbitmq");
    private static final int RABBITMQ_PORT =
            Integer.parseInt(environmentOrDefault("RABBITMQ_IT_PORT", "5671"));
    private static final SSLContext TEST_SSL_CONTEXT = createSslContext();

    @BeforeAll
    static void requireIntegrationConfiguration() {
        requireEnvironment("RABBITMQ_IT_PUBLISHER_SECRET");
        requireEnvironment("RABBITMQ_IT_READER_SECRET");
        requireEnvironment("RABBITMQ_IT_JWT_PRIVATE_KEY");
        requireEnvironment("RABBITMQ_IT_CA_CERT");
    }

    @Test
    void validPublisherTokenAuthenticatesAndPublishes() throws Exception {
        String token = requestToken(PUBLISHER_CLIENT_ID,
                System.getenv("RABBITMQ_IT_PUBLISHER_SECRET"), PUBLISH_SCOPE);

        try (Connection connection = connect(token);
             Channel channel = connection.createChannel()) {
            channel.confirmSelect();
            channel.basicPublish(TRAFFIC_EXCHANGE, "", null,
                    "rabbitmq-oauth-it".getBytes(StandardCharsets.UTF_8));
            channel.waitForConfirmsOrDie(Duration.ofSeconds(5).toMillis());
        }
    }

    @Test
    void validReaderTokenCanReadItsQueue() throws Exception {
        String token = requestToken(READER_CLIENT_ID,
                System.getenv("RABBITMQ_IT_READER_SECRET"), READ_SCOPE);

        try (Connection connection = connect(token);
             Channel channel = connection.createChannel()) {
            channel.basicGet(DASHBOARD_QUEUE, true);
        }
    }

    @Test
    void rejectsSignatureSignedByUnknownKey() throws Exception {
        String token = requestPublisherToken();
        assertAuthenticationRejected(resign(token, SIGNING_KEY, "unknown-it-kid"));
    }

    @Test
    void rejectsSignatureSignedByDifferentKeyWithKnownKeyId() throws Exception {
        String token = requestPublisherToken();
        assertAuthenticationRejected(resign(token, UNTRUSTED_KEY.getPrivate(),
                "rabbitmq-it-rsa"));
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String token = requestPublisherToken();
        SignedJWT original = SignedJWT.parse(token);
        JWTClaimsSet expiredClaims = new JWTClaimsSet.Builder(original.getJWTClaimsSet())
                .expirationTime(Date.from(Instant.now().minusSeconds(30)))
                .build();
        assertAuthenticationRejected(sign(expiredClaims, "rabbitmq-it-rsa", SIGNING_KEY));
    }

    @Test
    void rejectsWrongAudience() throws Exception {
        String token = requestPublisherToken();
        SignedJWT original = SignedJWT.parse(token);
        JWTClaimsSet wrongAudienceClaims = new JWTClaimsSet.Builder(original.getJWTClaimsSet())
                .audience("wrong-rabbitmq-it-audience")
                .build();
        assertAuthenticationRejected(sign(wrongAudienceClaims, "rabbitmq-it-rsa", SIGNING_KEY));
    }

    @Test
    void authenticatedReaderCannotDeclareAQueueWithoutConfigureScope() throws Exception {
        String token = requestToken(READER_CLIENT_ID,
                System.getenv("RABBITMQ_IT_READER_SECRET"), READ_SCOPE);

        try (Connection connection = connect(token)) {
            Channel channel = connection.createChannel();
            IOException denial = assertThrows(IOException.class,
                    () -> channel.queueDeclare("unauthorized-it-queue", true, false, false, null));
            assertTrue(denial.getCause() instanceof ShutdownSignalException);
            ShutdownSignalException shutdown = (ShutdownSignalException) denial.getCause();
            assertTrue(shutdown.getReason() instanceof AMQP.Channel.Close);
            assertEquals(403, ((AMQP.Channel.Close) shutdown.getReason()).getReplyCode());
        }
    }

    @Test
    void rejectsConnectionWithoutAnOauthToken() {
        ConnectionFactory factory = connectionFactory("");
        assertThrows(IOException.class, () -> factory.newConnection("rabbitmq-it-no-token"));
    }

    @Test
    void documentsRuntimeBehaviorForDifferentIssuerClaim() throws Exception {
        String token = requestPublisherToken();
        SignedJWT original = SignedJWT.parse(token);
        JWTClaimsSet differentIssuerClaims = new JWTClaimsSet.Builder(original.getJWTClaimsSet())
                .issuer("https://different-issuer.invalid")
                .build();
        String tokenWithDifferentIssuer =
                sign(differentIssuerClaims, "rabbitmq-it-rsa", SIGNING_KEY);

        assertDoesNotThrow(() -> {
            try (Connection connection = connect(tokenWithDifferentIssuer);
                 Channel ignored = connection.createChannel()) {
                assertTrue(connection.isOpen());
            }
        });
    }

    private static String requestPublisherToken() throws Exception {
        return requestToken(PUBLISHER_CLIENT_ID,
                System.getenv("RABBITMQ_IT_PUBLISHER_SECRET"), PUBLISH_SCOPE);
    }

    private static String requestToken(String clientId, String clientSecret, String scope)
            throws Exception {
        String credentials = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        String form = "grant_type=client_credentials&scope=" + scope;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(AUTH_BASE_URL + "/oauth2/token"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response =
                HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Test Auth Server token request failed with HTTP " + response.statusCode());
        }
        JsonNode responseBody = OBJECT_MAPPER.readTree(response.body());
        String token = responseBody.path("access_token").asText();
        if (token.isBlank()) {
            throw new IllegalStateException("Test Auth Server returned an empty access token");
        }
        return token;
    }

    private static Connection connect(String token) throws Exception {
        return connectionFactory(token).newConnection("rabbitmq-oauth-it");
    }

    private static ConnectionFactory connectionFactory(String token) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBITMQ_HOST);
        factory.setPort(RABBITMQ_PORT);
        factory.setVirtualHost(VHOST);
        factory.setUsername("ignored-oauth-username");
        factory.setPassword(token);
        factory.setConnectionTimeout(5000);
        factory.setHandshakeTimeout(5000);
        factory.useSslProtocol(TEST_SSL_CONTEXT);
        factory.enableHostnameVerification();
        return factory;
    }

    private static void assertAuthenticationRejected(String token) {
        ConnectionFactory factory = connectionFactory(token);
        assertThrows(IOException.class, () -> factory.newConnection("rabbitmq-oauth-it-rejected"));
    }

    private static String resign(String token, PrivateKey key, String keyId) throws Exception {
        return sign(SignedJWT.parse(token).getJWTClaimsSet(), keyId, key);
    }

    private static String sign(JWTClaimsSet claims, String keyId, PrivateKey key) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static PrivateKey loadPrivateKey() {
        String path = System.getenv("RABBITMQ_IT_JWT_PRIVATE_KEY");
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            String pem = Files.readString(Path.of(path));
            String encoded = pem.replaceAll("-----BEGIN [^-]+-----", "")
                    .replaceAll("-----END [^-]+-----", "")
                    .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(encoded);
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load the test-only JWT signing key", exception);
        }
    }

    private static SSLContext createSslContext() {
        String path = System.getenv("RABBITMQ_IT_CA_CERT");
        if (path == null || path.isBlank()) {
            return null;
        }
        try (var input = Files.newInputStream(Path.of(path))) {
            X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(input);
            var keyStore = java.security.KeyStore.getInstance(
                    java.security.KeyStore.getDefaultType());
            keyStore.load(null);
            keyStore.setCertificateEntry("rabbitmq-it-ca", ca);
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keyStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagerFactory.getTrustManagers(), null);
            return context;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load the test-only TLS CA certificate", exception);
        }
    }

    private static HttpClient createHttpClient() {
        SSLContext sslContext = createSslContext();
        if (sslContext == null) {
            return HttpClient.newHttpClient();
        }
        return HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create an ephemeral test signing key", exception);
        }
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static void requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required integration-test environment variable is missing: " + name);
        }
    }
}

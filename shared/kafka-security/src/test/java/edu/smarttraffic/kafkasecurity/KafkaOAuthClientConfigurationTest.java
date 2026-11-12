package edu.smarttraffic.kafkasecurity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class KafkaOAuthClientConfigurationTest {
    private static final String TOKEN_ENDPOINT = "https://auth.example.test/oauth2/token";
    private static final String CALLBACK_HANDLER =
            "io.strimzi.kafka.oauth.client.JaasClientOauthLoginCallbackHandler";

    @Test
    void producerUsesOauthOverTlsAndIdempotentWrites() {
        Properties properties = configuration().producerProperties();

        assertEquals("SASL_SSL", properties.get("security.protocol"));
        assertEquals("OAUTHBEARER", properties.get("sasl.mechanism"));
        assertEquals(CALLBACK_HANDLER, properties.get("sasl.login.callback.handler.class"));
        assertEquals("all", properties.get("acks"));
        assertEquals("true", properties.get("enable.idempotence"));
        assertEquals("https", properties.get("ssl.endpoint.identification.algorithm"));

        String jaas = (String) properties.get("sasl.jaas.config");
        assertTrue(jaas.contains("oauth.token.endpoint.uri=\"" + TOKEN_ENDPOINT + "\""));
        assertTrue(jaas.contains("oauth.client.id=\"<service-client-id>\""));
        assertTrue(jaas.contains("oauth.client.secret=\"<service-client-secret>\""));
        assertFalse(jaas.contains("oauth.scope="));
    }

    @Test
    void consumerAndStreamsRequireTheirApprovedIdentifiers() {
        Properties consumer = configuration().consumerProperties("traffic-archiver");
        Properties streams = configuration().streamsProperties("traffic-density-processor");

        assertEquals("traffic-archiver", consumer.get("group.id"));
        assertEquals("false", consumer.get("enable.auto.commit"));
        assertEquals("traffic-density-processor", streams.get("application.id"));
        assertEquals("SASL_SSL", streams.get("security.protocol"));
    }

    @Test
    void optionalScopeAndTrustStoreArePassedWithoutLoggingOrDefaults() {
        Map<String, String> environment = environment();
        environment.put("KAFKA_OAUTH_SCOPE", "traffic:write");
        environment.put("KAFKA_SSL_TRUSTSTORE_LOCATION", "C:\\secrets\\kafka-truststore.p12");
        environment.put("KAFKA_SSL_TRUSTSTORE_PASSWORD", "<truststore-password>");
        environment.put("KAFKA_SSL_TRUSTSTORE_TYPE", "PKCS12");

        Properties properties = KafkaOAuthClientConfiguration.fromEnvironment(environment).producerProperties();

        assertTrue(((String) properties.get("sasl.jaas.config")).contains("oauth.scope=\"traffic:write\""));
        assertEquals("C:\\secrets\\kafka-truststore.p12", properties.get("ssl.truststore.location"));
        assertEquals("<truststore-password>", properties.get("ssl.truststore.password"));
        assertEquals("PKCS12", properties.get("ssl.truststore.type"));
    }

    @Test
    void rejectsMissingCredentialsAndInsecureTokenEndpoint() {
        Map<String, String> missingSecret = environment();
        missingSecret.remove("KAFKA_OAUTH_CLIENT_SECRET");

        assertThrows(
                IllegalArgumentException.class,
                () -> KafkaOAuthClientConfiguration.fromEnvironment(missingSecret));

        Map<String, String> insecureEndpoint = environment();
        insecureEndpoint.put("AUTH_TOKEN_ENDPOINT_URI", "http://auth.example.test/oauth2/token");

        assertThrows(
                IllegalArgumentException.class,
                () -> KafkaOAuthClientConfiguration.fromEnvironment(insecureEndpoint));
    }

    @Test
    void rejectsJaasControlCharactersAndTrustStorePasswordWithoutLocation() {
        Map<String, String> injection = environment();
        injection.put("KAFKA_OAUTH_CLIENT_ID", "client\nprincipal");

        assertThrows(
                IllegalArgumentException.class,
                () -> KafkaOAuthClientConfiguration.fromEnvironment(injection));

        Map<String, String> orphanPassword = environment();
        orphanPassword.put("KAFKA_SSL_TRUSTSTORE_PASSWORD", "<truststore-password>");

        assertThrows(
                IllegalArgumentException.class,
                () -> KafkaOAuthClientConfiguration.fromEnvironment(orphanPassword));
    }

    private static KafkaOAuthClientConfiguration configuration() {
        return KafkaOAuthClientConfiguration.fromEnvironment(environment());
    }

    private static Map<String, String> environment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("KAFKA_BOOTSTRAP_SERVERS", "kafka.example.test:9093");
        environment.put("AUTH_TOKEN_ENDPOINT_URI", TOKEN_ENDPOINT);
        environment.put("KAFKA_OAUTH_CLIENT_ID", "<service-client-id>");
        environment.put("KAFKA_OAUTH_CLIENT_SECRET", "<service-client-secret>");
        return environment;
    }
}

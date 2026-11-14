package edu.smarttraffic.kafkasecurity;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import io.strimzi.kafka.oauth.client.JaasClientOauthLoginCallbackHandler;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule;

public final class KafkaOAuthClientConfiguration {
    private static final String LOGIN_CALLBACK_HANDLER =
            JaasClientOauthLoginCallbackHandler.class.getName();

    private final String bootstrapServers;
    private final URI tokenEndpoint;
    private final String clientId;
    private final String clientSecret;
    private final String scope;
    private final String trustStoreLocation;
    private final String trustStorePassword;
    private final String trustStoreType;
    private final String authTrustStoreLocation;
    private final String authTrustStorePassword;
    private final String authTrustStoreType;

    private KafkaOAuthClientConfiguration(Map<String, String> environment) {
        Objects.requireNonNull(environment, "Environment is required");
        bootstrapServers = required(environment, "KAFKA_BOOTSTRAP_SERVERS");
        tokenEndpoint = httpsUri(required(environment, "AUTH_TOKEN_ENDPOINT_URI"));
        clientId = required(environment, "KAFKA_OAUTH_CLIENT_ID");
        clientSecret = required(environment, "KAFKA_OAUTH_CLIENT_SECRET");
        scope = optional(environment, "KAFKA_OAUTH_SCOPE");
        trustStoreLocation = optional(environment, "KAFKA_SSL_TRUSTSTORE_LOCATION");
        trustStorePassword = optional(environment, "KAFKA_SSL_TRUSTSTORE_PASSWORD");
        trustStoreType = optional(environment, "KAFKA_SSL_TRUSTSTORE_TYPE");
        authTrustStoreLocation = optional(environment, "AUTH_SSL_TRUSTSTORE_LOCATION");
        authTrustStorePassword = optional(environment, "AUTH_SSL_TRUSTSTORE_PASSWORD");
        authTrustStoreType = optional(environment, "AUTH_SSL_TRUSTSTORE_TYPE");

        validateJaasValue(clientId, "KAFKA_OAUTH_CLIENT_ID");
        validateJaasValue(clientSecret, "KAFKA_OAUTH_CLIENT_SECRET");
        validateJaasValue(scope, "KAFKA_OAUTH_SCOPE");
        validateJaasValue(tokenEndpoint.toString(), "AUTH_TOKEN_ENDPOINT_URI");
        validateJaasValue(authTrustStoreLocation, "AUTH_SSL_TRUSTSTORE_LOCATION");
        validateJaasValue(authTrustStorePassword, "AUTH_SSL_TRUSTSTORE_PASSWORD");
        validateJaasValue(authTrustStoreType, "AUTH_SSL_TRUSTSTORE_TYPE");

        if (trustStoreLocation.isEmpty() && !trustStorePassword.isEmpty()) {
            throw new IllegalArgumentException(
                    "KAFKA_SSL_TRUSTSTORE_PASSWORD requires KAFKA_SSL_TRUSTSTORE_LOCATION");
        }
        if (authTrustStoreLocation.isEmpty()
                && (!authTrustStorePassword.isEmpty() || !authTrustStoreType.isEmpty())) {
            throw new IllegalArgumentException(
                    "AUTH_SSL_TRUSTSTORE_PASSWORD and AUTH_SSL_TRUSTSTORE_TYPE require "
                            + "AUTH_SSL_TRUSTSTORE_LOCATION");
        }
    }

    public static KafkaOAuthClientConfiguration fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    public static KafkaOAuthClientConfiguration fromEnvironment(Map<String, String> environment) {
        return new KafkaOAuthClientConfiguration(environment);
    }

    public Properties producerProperties() {
        Properties properties = commonProperties();
        properties.put("acks", "all");
        properties.put("enable.idempotence", "true");
        return properties;
    }

    public Properties consumerProperties(String groupId) {
        Properties properties = commonProperties();
        properties.put("group.id", requiredValue(groupId, "Kafka consumer group"));
        properties.put("enable.auto.commit", "false");
        return properties;
    }

    public Properties streamsProperties(String applicationId) {
        Properties properties = commonProperties();
        properties.put("application.id", requiredValue(applicationId, "Kafka Streams application.id"));
        return properties;
    }

    private Properties commonProperties() {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", bootstrapServers);
        properties.put("security.protocol", "SASL_SSL");
        properties.put("sasl.mechanism", "OAUTHBEARER");
        properties.put("sasl.login.callback.handler.class", LOGIN_CALLBACK_HANDLER);
        properties.put("sasl.jaas.config", jaasConfiguration());
        properties.put("ssl.endpoint.identification.algorithm", "https");

        if (!trustStoreLocation.isEmpty()) {
            properties.put("ssl.truststore.location", trustStoreLocation);
        }
        if (!trustStorePassword.isEmpty()) {
            properties.put("ssl.truststore.password", trustStorePassword);
        }
        if (!trustStoreType.isEmpty()) {
            properties.put("ssl.truststore.type", trustStoreType);
        }

        return properties;
    }

    private String jaasConfiguration() {
        StringBuilder configuration = new StringBuilder(
                OAuthBearerLoginModule.class.getName()).append(" required")
                .append(" oauth.token.endpoint.uri=\"").append(escapeJaas(tokenEndpoint.toString())).append('"')
                .append(" oauth.client.id=\"").append(escapeJaas(clientId)).append('"')
                .append(" oauth.client.secret=\"").append(escapeJaas(clientSecret)).append('"');
        if (!scope.isEmpty()) {
            configuration.append(" oauth.scope=\"").append(escapeJaas(scope)).append('"');
        }
        appendJaasOption(configuration, "oauth.ssl.truststore.location", authTrustStoreLocation);
        appendJaasOption(configuration, "oauth.ssl.truststore.password", authTrustStorePassword);
        appendJaasOption(configuration, "oauth.ssl.truststore.type", authTrustStoreType);
        return configuration.append(';').toString();
    }

    private static void appendJaasOption(StringBuilder configuration, String option, String value) {
        if (!value.isEmpty()) {
            configuration.append(' ')
                    .append(option)
                    .append("=\"")
                    .append(escapeJaas(value))
                    .append('"');
        }
    }

    private static URI httpsUri(String value) {
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("AUTH_TOKEN_ENDPOINT_URI must be an HTTPS URL without user info");
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("AUTH_TOKEN_ENDPOINT_URI must be a valid HTTPS URL", exception);
        }
    }

    private static String required(Map<String, String> environment, String name) {
        return requiredValue(environment.get(name), name);
    }

    private static String requiredValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String normalized = value.trim();
        validateJaasValue(normalized, name);
        return normalized;
    }

    private static String optional(Map<String, String> environment, String name) {
        String value = environment.get(name);
        return value == null ? "" : value.trim();
    }

    private static void validateJaasValue(String value, String name) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
    }

    private static String escapeJaas(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

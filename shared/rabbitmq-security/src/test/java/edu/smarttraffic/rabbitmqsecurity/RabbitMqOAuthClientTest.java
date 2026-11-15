package edu.smarttraffic.rabbitmqsecurity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RabbitMqOAuthClientTest {
    @Test
    void requiresRabbitMqConnectionSettingsBeforeLoadingCertificates() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new RabbitMqOAuthClient(Map.of()));

        assertTrue(exception.getMessage().contains("RABBITMQ_HOST"));
    }

    @Test
    void doesNotExposeAccessTokenInDiagnosticText() {
        RabbitMqOAuthClient.AccessToken token =
                new RabbitMqOAuthClient.AccessToken("sensitive-token", Instant.EPOCH);

        assertFalse(token.toString().contains("sensitive-token"));
        assertTrue(token.toString().contains("<redacted>"));
    }
}

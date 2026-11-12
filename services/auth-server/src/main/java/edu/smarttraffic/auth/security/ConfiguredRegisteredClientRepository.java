package edu.smarttraffic.auth.security;

import edu.smarttraffic.auth.config.AuthServerProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

public final class ConfiguredRegisteredClientRepository implements RegisteredClientRepository {
    private final Map<String, RegisteredClient> clientsByClientId;

    public ConfiguredRegisteredClientRepository(
            AuthServerProperties properties,
            PasswordEncoder passwordEncoder) {
        Duration accessTokenTtl = properties.getAccessTokenTtl();
        if (accessTokenTtl == null || accessTokenTtl.isZero() || accessTokenTtl.isNegative()) {
            throw new IllegalStateException("smarttraffic.auth.access-token-ttl must be positive");
        }

        Map<String, RegisteredClient> configuredClients = new LinkedHashMap<>();
        properties.getClients().forEach((clientId, clientProperties) -> {
            validate(clientId, clientProperties);
            RegisteredClient registeredClient = RegisteredClient.withId(UUID.randomUUID().toString())
                    .clientId(clientId)
                    .clientSecret(passwordEncoder.encode(clientProperties.getSecret()))
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                    .scopes(scopes -> scopes.addAll(clientProperties.getScopes()))
                    .tokenSettings(TokenSettings.builder()
                            .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                            .accessTokenTimeToLive(accessTokenTtl)
                            .build())
                    .build();
            configuredClients.put(clientId, registeredClient);
        });
        clientsByClientId = Map.copyOf(configuredClients);
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        throw new UnsupportedOperationException("Client identities are managed through external configuration");
    }

    @Override
    public RegisteredClient findById(String id) {
        return clientsByClientId.values().stream()
                .filter(client -> client.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        return clientsByClientId.get(clientId);
    }

    private static void validate(String clientId, AuthServerProperties.Client client) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("Configured client IDs must not be blank");
        }
        if (client == null || client.getSecret() == null || client.getSecret().isBlank()) {
            throw new IllegalStateException("A client secret must be configured for " + clientId);
        }
        if (client.getScopes().isEmpty() || client.getScopes().stream().anyMatch(String::isBlank)) {
            throw new IllegalStateException("At least one non-blank scope must be configured for " + clientId);
        }
        if (client.getAudiences().isEmpty() || client.getAudiences().stream().anyMatch(String::isBlank)) {
            throw new IllegalStateException("At least one non-blank audience must be configured for " + clientId);
        }
    }
}

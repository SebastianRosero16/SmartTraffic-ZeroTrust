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
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
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
        addDashboardClient(configuredClients, properties.getDashboardClient(), accessTokenTtl, passwordEncoder);
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

    private static void addDashboardClient(
            Map<String, RegisteredClient> configuredClients,
            AuthServerProperties.DashboardClient client,
            Duration accessTokenTtl,
            PasswordEncoder passwordEncoder) {
        if (client == null || isBlank(client.getClientId()) && isBlank(client.getSecret())) {
            return;
        }
        if (isBlank(client.getClientId()) || isBlank(client.getSecret())) {
            throw new IllegalStateException("Dashboard client ID and secret must both be configured");
        }
        if (client.getRedirectUris().isEmpty()
                || client.getRedirectUris().stream().anyMatch(String::isBlank)) {
            throw new IllegalStateException("At least one dashboard client redirect URI must be configured");
        }
        if (client.getAudiences().isEmpty() || client.getAudiences().stream().anyMatch(String::isBlank)) {
            throw new IllegalStateException("At least one dashboard client audience must be configured");
        }
        if (configuredClients.containsKey(client.getClientId())) {
            throw new IllegalStateException("Dashboard client ID must be unique");
        }

        RegisteredClient.Builder registeredClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(client.getClientId())
                .clientSecret(passwordEncoder.encode(client.getSecret()))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .scope("openid")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                        .accessTokenTimeToLive(accessTokenTtl)
                        .build());
        client.getRedirectUris().forEach(registeredClient::redirectUri);
        configuredClients.put(client.getClientId(), registeredClient.build());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

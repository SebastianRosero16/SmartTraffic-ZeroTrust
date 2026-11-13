package edu.smarttraffic.auth.config;

import edu.smarttraffic.auth.security.ConfiguredRegisteredClientRepository;
import edu.smarttraffic.auth.security.RsaSigningKeyLoader;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings.Builder;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class AuthorizationServerConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                OAuth2AuthorizationServerConfigurer.authorizationServer();
        http.securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
                .with(authorizationServerConfigurer, Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain denyUnconfiguredRequests(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().denyAll());
        return http.build();
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(AuthServerProperties properties) {
        String issuer = properties.getIssuer();
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("smarttraffic.auth.issuer must be configured");
        }
        Builder settings = AuthorizationServerSettings.builder().issuer(issuer);
        return settings.build();
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(AuthServerProperties properties) {
        return new ImmutableJWKSet<>(new JWKSet(RsaSigningKeyLoader.load(properties)));
    }

    @Bean
    PasswordEncoder clientSecretPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(
            AuthServerProperties properties,
            PasswordEncoder clientSecretPasswordEncoder) {
        return new ConfiguredRegisteredClientRepository(properties, clientSecretPasswordEncoder);
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer(AuthServerProperties properties) {
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                String clientId = context.getRegisteredClient().getClientId();
                AuthServerProperties.Client client = properties.getClients().get(clientId);
                if (client == null || client.getAudiences().isEmpty()) {
                    throw new IllegalStateException("At least one audience must be configured for client " + clientId);
                }
                context.getClaims().audience(client.getAudiences());
                context.getClaims().id(UUID.randomUUID().toString());
                context.getClaims().claim("token_type", "Bearer");
                context.getJwsHeader()
                        .algorithm(SignatureAlgorithm.RS256)
                        .keyId(properties.getKeyId());
            }
        };
    }
}

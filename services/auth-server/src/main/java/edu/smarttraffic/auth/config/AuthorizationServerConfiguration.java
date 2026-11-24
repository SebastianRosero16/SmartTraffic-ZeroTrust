package edu.smarttraffic.auth.config;

import edu.smarttraffic.auth.security.ConfiguredRegisteredClientRepository;
import edu.smarttraffic.auth.security.RsaSigningKeyLoader;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
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
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

@Configuration
public class AuthorizationServerConfiguration {
    @Bean
    FilterRegistrationBean<TokenEndpointAuditFilter> tokenEndpointAuditFilter(
            AuthServerProperties properties) {
        FilterRegistrationBean<TokenEndpointAuditFilter> registration = new FilterRegistrationBean<>(
                new TokenEndpointAuditFilter(properties.getTest()));
        registration.addUrlPatterns("/oauth2/token");
        registration.setOrder(-100);
        return registration;
    }

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                OAuth2AuthorizationServerConfigurer.authorizationServer();
        http.securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
                .with(authorizationServerConfigurer, server -> server.oidc(Customizer.withDefaults()))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/.well-known/openid-configuration",
                                "/.well-known/oauth-authorization-server",
                                "/oauth2/jwks")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain interactiveLoginSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .permitAll());
        return http.build();
    }

    @Bean
    UserDetailsService dashboardUserDetailsService(JdbcTemplate jdbcTemplate) {
        return username -> jdbcTemplate.query(
                        """
                        SELECT username, password_hash, enabled
                        FROM dashboard_auth.dashboard_user
                        WHERE username = ?
                        """,
                        (resultSet, rowNumber) -> User.withUsername(resultSet.getString("username"))
                                .password(resultSet.getString("password_hash"))
                                .disabled(!resultSet.getBoolean("enabled"))
                                .authorities("ROLE_DASHBOARD_VIEWER")
                                .build(),
                        username)
                .stream()
                .findFirst()
                .orElseThrow(() -> new UsernameNotFoundException("Dashboard account not found"));
    }

    @Bean
    ApplicationRunner dashboardUserBootstrap(
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder,
            AuthServerProperties properties) {
        return args -> {
            AuthServerProperties.DashboardUser dashboardUser = properties.getDashboardUser();
            if (!dashboardUser.isBootstrapEnabled()) {
                return;
            }

            var usernames = jdbcTemplate.queryForList(
                    "SELECT username FROM dashboard_auth.dashboard_user",
                    String.class);
            if (usernames.size() == 1) {
                if (!usernames.get(0).equals(dashboardUser.getUsername())) {
                    throw new IllegalStateException(
                            "Configured dashboard username does not match the provisioned database account");
                }
                return;
            }
            if (!usernames.isEmpty()) {
                throw new IllegalStateException("Only one dashboard account is allowed");
            }
            if (dashboardUser.getUsername() == null || dashboardUser.getUsername().isBlank()) {
                throw new IllegalStateException("smarttraffic.auth.dashboard-user.username must be configured");
            }
            if (dashboardUser.getPasswordFile() == null) {
                throw new IllegalStateException(
                        "Set smarttraffic.auth.dashboard-user.password-file to provision the initial account");
            }

            String password = readDashboardPassword(dashboardUser.getPasswordFile());
            if (password.length() < 12 || password.indexOf('\n') >= 0 || password.indexOf('\r') >= 0) {
                throw new IllegalStateException(
                        "The initial dashboard password must be a single line of at least 12 characters");
            }
            jdbcTemplate.update(
                    """
                    INSERT INTO dashboard_auth.dashboard_user (username, password_hash, enabled)
                    VALUES (?, ?, TRUE)
                    """,
                    dashboardUser.getUsername(),
                    passwordEncoder.encode(password));
        };
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
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(
            AuthServerProperties properties,
            PasswordEncoder passwordEncoder) {
        return new ConfiguredRegisteredClientRepository(properties, passwordEncoder);
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer(AuthServerProperties properties) {
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                String clientId = context.getRegisteredClient().getClientId();
                AuthServerProperties.Client client = properties.getClients().get(clientId);
                var dashboardClient = properties.getDashboardClient();
                var audiences = client != null
                        ? client.getAudiences()
                        : dashboardClient != null && clientId.equals(dashboardClient.getClientId())
                                ? dashboardClient.getAudiences()
                                : null;
                if (audiences == null || audiences.isEmpty()) {
                    throw new IllegalStateException("At least one audience must be configured for client " + clientId);
                }
                context.getClaims().audience(audiences);
                context.getClaims().id(UUID.randomUUID().toString());
                context.getClaims().claim("token_type", "Bearer");
                context.getJwsHeader()
                        .algorithm(SignatureAlgorithm.RS256)
                        .keyId(properties.getKeyId());
            }
        };
    }

    private static String readDashboardPassword(Path passwordFile) throws IOException {
        String password = Files.readString(passwordFile, StandardCharsets.UTF_8);
        if (password.endsWith("\r\n")) {
            return password.substring(0, password.length() - 2);
        }
        if (password.endsWith("\n") || password.endsWith("\r")) {
            return password.substring(0, password.length() - 1);
        }
        return password;
    }
}

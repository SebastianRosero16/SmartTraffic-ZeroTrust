package edu.smarttraffic.auth;

import edu.smarttraffic.auth.config.AuthServerProperties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationServerIntegrationTest {
    private static final String ISSUER = "https://auth.test.smarttraffic";
    private static final String KEY_ID = "test-signing-key";
    private static final String DASHBOARD_REDIRECT_URI =
            "http://localhost:8080/login/oauth2/code/dashboard";
    private static final String DASHBOARD_PASSWORD = "test-only-dashboard-password";
    private static final Path KEY_DIRECTORY;
    private static final KeyPair TEST_KEY_PAIR;

    static {
        try {
            KEY_DIRECTORY = Files.createTempDirectory("smarttraffic-auth-test-");
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            TEST_KEY_PAIR = generator.generateKeyPair();
            writePem(KEY_DIRECTORY.resolve("private.pem"), "PRIVATE KEY", TEST_KEY_PAIR.getPrivate().getEncoded());
            writePem(KEY_DIRECTORY.resolve("public.pem"), "PUBLIC KEY", TEST_KEY_PAIR.getPublic().getEncoded());
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private AuthServerProperties authServerProperties;

    @Autowired
    private ApplicationRunner dashboardUserBootstrap;

    @BeforeEach
    void provisionTestDashboardUser() {
        jdbcTemplate.update("DELETE FROM dashboard_auth.dashboard_user");
        jdbcTemplate.update(
                """
                INSERT INTO dashboard_auth.dashboard_user (username, password_hash, enabled)
                VALUES (?, ?, TRUE)
                """,
                "dashboard-viewer",
                passwordEncoder.encode(DASHBOARD_PASSWORD));
    }

    @DynamicPropertySource
    static void authServerProperties(DynamicPropertyRegistry registry) {
        registry.add("smarttraffic.auth.issuer", () -> ISSUER);
        registry.add("smarttraffic.auth.access-token-ttl", () -> "PT2M");
        registry.add("smarttraffic.auth.key-id", () -> KEY_ID);
        registry.add("smarttraffic.auth.private-key-path", () -> KEY_DIRECTORY.resolve("private.pem").toString());
        registry.add("smarttraffic.auth.public-key-path", () -> KEY_DIRECTORY.resolve("public.pem").toString());
        registry.add("smarttraffic.auth.clients.sensor-client.secret", () -> "test-only-sensor-secret");
        registry.add("smarttraffic.auth.clients.sensor-client.scopes[0]", () -> "traffic.readings.publish");
        registry.add("smarttraffic.auth.clients.sensor-client.audiences[0]", () -> "urn:smarttraffic:kafka");
        registry.add("smarttraffic.auth.dashboard-client.client-id", () -> "dashboard-bff");
        registry.add("smarttraffic.auth.dashboard-client.secret", () -> "test-only-dashboard-client-secret");
        registry.add("smarttraffic.auth.dashboard-client.redirect-uris[0]", () -> DASHBOARD_REDIRECT_URI);
        registry.add("smarttraffic.auth.dashboard-client.audiences[0]", () -> "urn:smarttraffic:dashboard");
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:auth-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:dashboard-auth-test-schema.sql");
        registry.add("smarttraffic.auth.dashboard-user.bootstrap-enabled", () -> "false");
    }

    @AfterAll
    static void removeGeneratedKeys() throws IOException {
        Files.deleteIfExists(KEY_DIRECTORY.resolve("private.pem"));
        Files.deleteIfExists(KEY_DIRECTORY.resolve("public.pem"));
        Files.deleteIfExists(KEY_DIRECTORY);
    }

    @Test
    void issuesRs256TokenWithConfiguredClaimsAndPublicJwks() throws Exception {
        MvcResult tokenResponse = mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic("sensor-client", "test-only-sensor-secret"))
                        .param("grant_type", "client_credentials")
                        .param("scope", "traffic.readings.publish"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode tokenBody = objectMapper.readTree(tokenResponse.getResponse().getContentAsString());
        String accessToken = tokenBody.path("access_token").asText();
        assertFalse(accessToken.isBlank());
        assertEquals("Bearer", tokenBody.path("token_type").asText());

        SignedJWT signedJwt = SignedJWT.parse(accessToken);
        assertEquals(JWSAlgorithm.RS256, signedJwt.getHeader().getAlgorithm());
        assertEquals(KEY_ID, signedJwt.getHeader().getKeyID());
        assertTrue(signedJwt.verify(new RSASSAVerifier((RSAPublicKey) TEST_KEY_PAIR.getPublic())));

        var claims = signedJwt.getJWTClaimsSet();
        assertEquals(ISSUER, claims.getIssuer());
        assertEquals("sensor-client", claims.getSubject());
        assertEquals("urn:smarttraffic:kafka", claims.getAudience().get(0));
        assertEquals("Bearer", claims.getStringClaim("token_type"));
        assertEquals("traffic.readings.publish", claims.getStringListClaim("scope").get(0));
        assertNotNull(claims.getIssueTime());
        assertNotNull(claims.getExpirationTime());
        assertNotNull(claims.getJWTID());
        assertTrue(claims.getExpirationTime().after(claims.getIssueTime()));
        assertTrue(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime() <= 120_000L);

        JsonNode publicJwk = readJwks().path("keys").get(0);
        assertEquals(KEY_ID, publicJwk.path("kid").asText());
        assertEquals("RS256", publicJwk.path("alg").asText());
        assertFalse(publicJwk.has("d"));
        assertFalse(publicJwk.has("p"));
        assertFalse(publicJwk.has("q"));
    }

    @Test
    void rejectsUnknownClient() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic("unknown-client", "unknown-secret"))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    @Test
    void rejectsIncorrectClientSecret() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic("sensor-client", "incorrect-secret"))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    @Test
    void rejectsScopesNotAssignedToTheClient() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic("sensor-client", "test-only-sensor-secret"))
                        .param("grant_type", "client_credentials")
                        .param("scope", "traffic.readings.consume"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
    }

    @Test
    void rendersBrandedLoginPageWithCsrfProtection() throws Exception {
        String page = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(page.contains("Movilidad que se entiende"));
        assertTrue(page.contains("name=\"username\""));
        assertTrue(page.contains("name=\"password\""));
        assertTrue(page.contains("name=\"_csrf\""));
        assertFalse(page.contains("Usuario o contraseña incorrectos"));
        assertFalse(page.contains("Tu sesión se cerró correctamente"));
    }

    @Test
    void showsTheLoginStateMessageFromTheRedirectParameter() throws Exception {
        String errorPage = mockMvc.perform(get("/login").param("error", ""))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertTrue(errorPage.contains("Usuario o contraseña incorrectos"));
        assertFalse(errorPage.contains("Tu sesión se cerró correctamente"));

        String logoutPage = mockMvc.perform(get("/login").param("logout", ""))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertTrue(logoutPage.contains("Tu sesión se cerró correctamente"));
        assertFalse(logoutPage.contains("Usuario o contraseña incorrectos"));
    }

    @Test
    void rejectsIncorrectDashboardPassword() throws Exception {
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "dashboard-viewer")
                        .param("password", "incorrect-password"))
                .andExpect(status().isFound())
                .andExpect(MockMvcResultMatchers.redirectedUrl("/login?error"));
    }

    @Test
    void loginPageProvidesCsrfForAProtectedLogoutForm() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "dashboard-viewer")
                        .param("password", DASHBOARD_PASSWORD))
                .andExpect(status().isFound())
                .andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertNotNull(session);

        String loginPage = mockMvc.perform(get("/login").session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Matcher csrfField = Pattern.compile(
                        "<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"")
                .matcher(loginPage);
        assertTrue(csrfField.find());
        String csrfToken = csrfField.group(1);

        mockMvc.perform(post("/logout").session(session))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/logout").session(session).param("_csrf", csrfToken))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login?logout"))
                .andExpect(unauthenticated());
    }

    @Test
    void bootstrapsSingleDashboardUserWithEncodedPassword() throws Exception {
        jdbcTemplate.update("DELETE FROM dashboard_auth.dashboard_user");
        Path passwordFile = Files.createTempFile("smarttraffic-dashboard-password-", ".txt");
        Files.writeString(passwordFile, DASHBOARD_PASSWORD, StandardCharsets.UTF_8);
        AuthServerProperties.DashboardUser dashboardUser = authServerProperties.getDashboardUser();
        dashboardUser.setUsername("dashboard-viewer");
        dashboardUser.setPasswordFile(passwordFile);
        dashboardUser.setBootstrapEnabled(true);

        try {
            dashboardUserBootstrap.run(new DefaultApplicationArguments(new String[0]));
            String storedHash = jdbcTemplate.queryForObject(
                    "SELECT password_hash FROM dashboard_auth.dashboard_user WHERE username = ?",
                    String.class,
                    "dashboard-viewer");
            assertNotNull(storedHash);
            assertTrue(passwordEncoder.matches(DASHBOARD_PASSWORD, storedHash));
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM dashboard_auth.dashboard_user",
                    Integer.class));
        } finally {
            dashboardUser.setPasswordFile(null);
            dashboardUser.setBootstrapEnabled(false);
            Files.deleteIfExists(passwordFile);
        }
    }

    @Test
    void databaseRejectsASecondDashboardUser() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                """
                INSERT INTO dashboard_auth.dashboard_user (username, password_hash, enabled)
                VALUES (?, ?, TRUE)
                """,
                "another-viewer",
                passwordEncoder.encode("another-test-password")));
    }

    @Test
    void authenticatesDashboardUserAndStartsAuthorizationCodeFlowWithPkce() throws Exception {
        var dashboardClient = registeredClientRepository.findByClientId("dashboard-bff");
        assertNotNull(dashboardClient);
        assertTrue(dashboardClient.getAuthorizationGrantTypes().contains(AuthorizationGrantType.AUTHORIZATION_CODE));

        String verifier = "0123456789012345678901234567890123456789012";
        String challenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.UTF_8)));
        MvcResult authorizationStart = mockMvc.perform(get("/oauth2/authorize")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "dashboard-bff")
                        .queryParam("redirect_uri", DASHBOARD_REDIRECT_URI)
                        .queryParam("scope", "openid")
                        .queryParam("state", "test-state")
                        .queryParam("code_challenge", challenge)
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().isFound())
                .andReturn();

        assertTrue(authorizationStart.getResponse().getHeader("Location").contains("/login"));
        MockHttpSession session = (MockHttpSession) authorizationStart.getRequest().getSession(false);
        assertNotNull(session);

        mockMvc.perform(post("/login")
                        .session(session)
                        .with(csrf())
                        .param("username", "dashboard-viewer")
                        .param("password", DASHBOARD_PASSWORD))
                .andExpect(status().isFound());

        MvcResult authorizationComplete = mockMvc.perform(get("/oauth2/authorize")
                        .session(session)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "dashboard-bff")
                        .queryParam("redirect_uri", DASHBOARD_REDIRECT_URI)
                        .queryParam("scope", "openid")
                        .queryParam("state", "test-state")
                        .queryParam("code_challenge", challenge)
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().isFound())
                .andReturn();

        String redirect = authorizationComplete.getResponse().getHeader("Location");
        assertTrue(redirect.startsWith(DASHBOARD_REDIRECT_URI));
        assertTrue(redirect.contains("code="));
        assertTrue(redirect.contains("state=test-state"));
        String authorizationCode = UriComponentsBuilder.fromUriString(redirect)
                .build()
                .getQueryParams()
                .getFirst("code");

        mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic("dashboard-bff", "test-only-dashboard-client-secret"))
                        .param("grant_type", "authorization_code")
                        .param("code", authorizationCode)
                        .param("redirect_uri", DASHBOARD_REDIRECT_URI)
                        .param("code_verifier", verifier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id_token").isNotEmpty());
    }

    @Test
    void publishesOnlyPublicKeyMaterialFromJwksEndpoint() throws Exception {
        JsonNode jwks = readJwks();
        JsonNode key = jwks.path("keys").get(0);

        assertEquals(KEY_ID, key.path("kid").asText());
        assertEquals("RSA", key.path("kty").asText());
        assertTrue(key.has("n"));
        assertTrue(key.has("e"));
        assertFalse(key.has("d"));
        assertFalse(key.has("p"));
        assertFalse(key.has("q"));
    }

    @Test
    void servesOpenIdProviderConfigurationWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value(ISSUER))
                .andExpect(jsonPath("$.jwks_uri").value(ISSUER + "/oauth2/jwks"));
    }

    private JsonNode readJwks() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static void writePem(Path path, String type, byte[] encodedKey) throws IOException {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encodedKey);
        Files.writeString(path, "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n");
    }
}

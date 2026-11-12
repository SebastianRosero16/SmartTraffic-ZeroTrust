package edu.smarttraffic.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationServerIntegrationTest {
    private static final String ISSUER = "https://auth.test.smarttraffic";
    private static final String KEY_ID = "test-signing-key";
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

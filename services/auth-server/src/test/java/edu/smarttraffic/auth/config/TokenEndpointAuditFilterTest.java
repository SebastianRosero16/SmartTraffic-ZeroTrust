package edu.smarttraffic.auth.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TokenEndpointAuditFilterTest {
    private static final String CLIENT_ID = "simulator-client";
    private static final String CLIENT_SECRET = "test-only-secret";

    @TempDir
    Path tempDirectory;

    @Test
    void recordsSanitizedTokenMetadataAndPreservesSuccessfulResponse() throws Exception {
        Path auditPath = tempDirectory.resolve("token-audit.tsv");
        TokenEndpointAuditFilter filter = new TokenEndpointAuditFilter(
                properties(auditPath, tempDirectory.resolve("reject.marker")));
        String accessToken = testJwt();
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
            httpResponse.setStatus(200);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"access_token\":\"" + accessToken + "\"}");
        });

        String audit = Files.readString(auditPath, StandardCharsets.UTF_8);
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains(accessToken));
        assertTrue(audit.contains("clientId=" + CLIENT_ID));
        assertTrue(audit.contains("status=200"));
        assertTrue(audit.matches("(?s).*requestId=[0-9a-f-]{36}.*"));
        assertTrue(audit.contains("rejectionArmed=false"));
        assertTrue(audit.contains("rejectionApplied=false"));
        assertTrue(audit.contains("iatEpochSeconds=1700000000"));
        assertTrue(audit.contains("expEpochSeconds=1700000300"));
        assertFalse(audit.contains(accessToken));
        assertFalse(audit.contains(CLIENT_SECRET));
    }

    @Test
    void rejectionMarkerReturnsUnauthorizedAndAuditsOnlyClientMetadata() throws Exception {
        Path auditPath = tempDirectory.resolve("token-audit.tsv");
        Path markerPath = Files.createFile(tempDirectory.resolve("reject.marker"));
        TokenEndpointAuditFilter filter = new TokenEndpointAuditFilter(properties(auditPath, markerPath));
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] downstreamCalled = {false};

        filter.doFilter(request, response, (servletRequest, servletResponse) -> downstreamCalled[0] = true);

        String audit = Files.readString(auditPath, StandardCharsets.UTF_8);
        assertEquals(401, response.getStatus());
        assertFalse(downstreamCalled[0]);
        assertTrue(audit.contains("clientId=" + CLIENT_ID));
        assertTrue(audit.contains("status=401"));
        assertTrue(audit.matches("(?s).*requestId=[0-9a-f-]{36}.*"));
        assertTrue(audit.contains("rejectionArmed=true"));
        assertTrue(audit.contains("rejectionApplied=true"));
        assertFalse(audit.contains(CLIENT_SECRET));
    }

    @Test
    void recordsArmedMarkerWithoutRejectingAnotherClient() throws Exception {
        Path auditPath = tempDirectory.resolve("token-audit.tsv");
        Path markerPath = Files.createFile(tempDirectory.resolve("reject.marker"));
        TokenEndpointAuditFilter filter = new TokenEndpointAuditFilter(properties(auditPath, markerPath));
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] downstreamCalled = {false};

        filter.doFilter(request("interbroker-client"), response, (servletRequest, servletResponse) -> {
            downstreamCalled[0] = true;
            HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
            httpResponse.setStatus(200);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"access_token\":\"" + testJwt() + "\"}");
        });

        String audit = Files.readString(auditPath, StandardCharsets.UTF_8);
        assertEquals(200, response.getStatus());
        assertTrue(downstreamCalled[0]);
        assertTrue(audit.contains("clientId=interbroker-client"));
        assertTrue(audit.contains("rejectionArmed=true"));
        assertTrue(audit.contains("rejectionApplied=false"));
        assertFalse(audit.contains(CLIENT_SECRET));
    }

    @Test
    void assignsUniqueRequestIdsToAppendedAuditEvents() throws Exception {
        Path auditPath = tempDirectory.resolve("token-audit.tsv");
        TokenEndpointAuditFilter filter =
                new TokenEndpointAuditFilter(properties(auditPath, tempDirectory.resolve("reject.marker")));

        for (int index = 0; index < 2; index++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request(), response, (servletRequest, servletResponse) -> {
                HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
                httpResponse.setStatus(200);
                httpResponse.setContentType("application/json");
                httpResponse.getWriter().write("{\"access_token\":\"" + testJwt() + "\"}");
            });
        }

        List<String> requestIds = Files.readAllLines(auditPath, StandardCharsets.UTF_8).stream()
                .map(line -> line.substring("requestId=".length(), line.indexOf('\t')))
                .toList();
        assertEquals(2, requestIds.size());
        assertNotEquals(requestIds.get(0), requestIds.get(1));
    }

    private static AuthServerProperties.TestEndpoint properties(Path auditPath, Path markerPath) {
        AuthServerProperties.TestEndpoint properties = new AuthServerProperties.TestEndpoint();
        properties.setAuditPath(auditPath);
        properties.setRejectionMarkerPath(markerPath);
        properties.setRejectedClientId(CLIENT_ID);
        return properties;
    }

    private static MockHttpServletRequest request() {
        return request(CLIENT_ID);
    }

    private static MockHttpServletRequest request(String clientId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
        String credentials = clientId + ":" + CLIENT_SECRET;
        request.addHeader(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        return request;
    }

    private static String testJwt() {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"iat\":1700000000,\"exp\":1700000300}".getBytes(StandardCharsets.UTF_8));
        return header + "." + claims + ".signature";
    }
}

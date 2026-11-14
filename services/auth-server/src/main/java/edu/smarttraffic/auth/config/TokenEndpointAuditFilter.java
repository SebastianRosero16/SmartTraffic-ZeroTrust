package edu.smarttraffic.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.web.util.ContentCachingResponseWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.filter.OncePerRequestFilter;

final class TokenEndpointAuditFilter extends OncePerRequestFilter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Path auditPath;
    private final Path rejectionMarkerPath;
    private final String rejectedClientId;

    TokenEndpointAuditFilter(AuthServerProperties.TestEndpoint properties) {
        auditPath = properties.getAuditPath();
        rejectionMarkerPath = properties.getRejectionMarkerPath();
        rejectedClientId = properties.getRejectedClientId();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!"/oauth2/token".equals(request.getRequestURI()) || auditPath == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String requestId = UUID.randomUUID().toString();
        String clientId = clientId(request);
        boolean rejectionArmed = rejectionMarkerPath != null
                && Files.exists(rejectionMarkerPath);
        boolean rejectionApplied = rejectionArmed
                && rejectedClientId != null
                && rejectedClientId.equals(clientId);
        ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);
        try {
            if (rejectionApplied) {
                cachedResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            } else {
                filterChain.doFilter(request, cachedResponse);
            }
            TokenTimes tokenTimes = tokenTimes(cachedResponse);
            record(
                    requestId,
                    clientId,
                    cachedResponse.getStatus(),
                    rejectionArmed,
                    rejectionApplied,
                    tokenTimes);
        } finally {
            cachedResponse.copyBodyToResponse();
        }
    }

    private TokenTimes tokenTimes(ContentCachingResponseWrapper response) throws IOException {
        if (response.getStatus() != HttpServletResponse.SC_OK) {
            return null;
        }

        JsonNode tokenResponse = OBJECT_MAPPER.readTree(response.getContentAsByteArray());
        String accessToken = tokenResponse.path("access_token").asText();
        String[] parts = accessToken.split("\\.", -1);
        if (parts.length != 3 || parts[1].isBlank()) {
            throw new IOException("Successful token response did not contain a JWT access token");
        }

        JsonNode claims = OBJECT_MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
        JsonNode issuedAt = claims.get("iat");
        JsonNode expiresAt = claims.get("exp");
        if (issuedAt == null || !issuedAt.isIntegralNumber()
                || expiresAt == null || !expiresAt.isIntegralNumber()
                || expiresAt.longValue() <= issuedAt.longValue()) {
            throw new IOException("JWT access token did not contain valid iat and exp claims");
        }
        return new TokenTimes(issuedAt.longValue(), expiresAt.longValue());
    }

    private synchronized void record(
            String requestId,
            String clientId,
            int status,
            boolean rejectionArmed,
            boolean rejectionApplied,
            TokenTimes tokenTimes) throws IOException {
        Path parent = auditPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String safeClientId = clientId != null && clientId.matches("[A-Za-z0-9._-]{1,128}")
                ? clientId
                : "<missing-or-invalid>";
        StringBuilder line = new StringBuilder()
                .append("requestId=").append(requestId)
                .append('\t').append("time=").append(Instant.now())
                .append('\t').append("clientId=").append(safeClientId)
                .append('\t').append("status=").append(status)
                .append('\t').append("rejectionArmed=").append(rejectionArmed)
                .append('\t').append("rejectionApplied=").append(rejectionApplied);
        if (tokenTimes != null) {
            line.append('\t').append("iatEpochSeconds=").append(tokenTimes.issuedAtEpochSeconds())
                    .append('\t').append("expEpochSeconds=").append(tokenTimes.expiresAtEpochSeconds());
        }
        Files.writeString(
                auditPath,
                line.append(System.lineSeparator()).toString(),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private String clientId(HttpServletRequest request) {
        String parameter = request.getParameter("client_id");
        if (parameter != null) {
            return parameter;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Basic ")) {
            return null;
        }
        try {
            String credentials = new String(
                    Base64.getDecoder().decode(authorization.substring("Basic ".length())),
                    StandardCharsets.UTF_8);
            int separator = credentials.indexOf(':');
            return separator < 0 ? null : credentials.substring(0, separator);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private record TokenTimes(long issuedAtEpochSeconds, long expiresAtEpochSeconds) {}
}

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
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.filter.OncePerRequestFilter;

final class TokenEndpointAuditFilter extends OncePerRequestFilter {
    private final Path auditPath;
    private final Path rejectionMarkerPath;
    private final String rejectedClientId;
    private final AtomicInteger requestCount = new AtomicInteger();

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

        String clientId = clientId(request);
        boolean reject = rejectionMarkerPath != null
                && Files.exists(rejectionMarkerPath)
                && rejectedClientId != null
                && rejectedClientId.equals(clientId);
        if (reject) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        } else {
            filterChain.doFilter(request, response);
        }
        record(requestCount.incrementAndGet(), clientId, response.getStatus());
    }

    private void record(int count, String clientId, int status) throws IOException {
        Path parent = auditPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String safeClientId = clientId == null ? "<missing>" : clientId;
        Files.writeString(
                auditPath,
                count + " time=" + Instant.now() + " clientId=" + safeClientId
                        + " status=" + status + System.lineSeparator(),
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
}

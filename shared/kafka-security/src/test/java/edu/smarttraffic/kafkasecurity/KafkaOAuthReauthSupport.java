package edu.smarttraffic.kafkasecurity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class KafkaOAuthReauthSupport {
    static final long EXPECTED_TOKEN_TTL_SECONDS = 300;
    static final long EXPECTED_REAUTH_INTERVAL_MS = 240_000;
    static final long MAX_CASE_WAIT_MS = Duration.ofMinutes(6).toMillis();
    static final long POST_EXPIRY_GRACE_MS = Duration.ofSeconds(60).toMillis();

    private KafkaOAuthReauthSupport() {}

    static List<TokenAuditEvent> readTokenAudit(Path path) throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }

        String content = Files.readString(path, StandardCharsets.UTF_8);
        int completeLineEnd = content.lastIndexOf('\n');
        if (completeLineEnd < 0) {
            return List.of();
        }

        List<TokenAuditEvent> events = new ArrayList<>();
        Set<String> requestIds = new HashSet<>();
        for (String line : content.substring(0, completeLineEnd).split("\\R")) {
            if (line.isBlank()) {
                continue;
            }

            Map<String, String> fields = new HashMap<>();
            for (String field : line.split("\t")) {
                int separator = field.indexOf('=');
                if (separator > 0) {
                    fields.put(field.substring(0, separator), field.substring(separator + 1));
                }
            }

            String requestId = required(fields, "requestId");
            try {
                UUID.fromString(requestId);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Token audit contains an invalid request ID", exception);
            }
            if (!requestIds.add(requestId)) {
                throw new IOException("Token audit contains a duplicate request ID");
            }

            events.add(new TokenAuditEvent(
                    requestId,
                    required(fields, "clientId"),
                    parseInt(fields, "status"),
                    parseBoolean(fields, "rejectionArmed"),
                    parseBoolean(fields, "rejectionApplied"),
                    optionalLong(fields, "iatEpochSeconds"),
                    optionalLong(fields, "expEpochSeconds")));
        }
        return events;
    }

    static Set<String> requestIds(Path path) throws IOException {
        Set<String> requestIds = new HashSet<>();
        for (TokenAuditEvent event : readTokenAudit(path)) {
            requestIds.add(event.requestId());
        }
        return Set.copyOf(requestIds);
    }

    static List<TokenAuditEvent> eventsSince(
            Path path, Set<String> knownRequestIds, String clientId) throws IOException {
        return readTokenAudit(path).stream()
                .filter(event -> !knownRequestIds.contains(event.requestId()))
                .filter(event -> event.clientId().equals(clientId))
                .toList();
    }

    static ReauthDeadline newCaseLimit(long caseStartNanos) {
        long deadlineNanos = caseStartNanos + TimeUnit.MILLISECONDS.toNanos(MAX_CASE_WAIT_MS);
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        return new ReauthDeadline(
                "case",
                deadlineNanos,
                System.currentTimeMillis() + Math.max(0, remainingMs),
                -1);
    }

    static ReauthDeadline forRenewal(
            String caseName,
            long caseStartNanos,
            TokenAuditEvent initialToken,
            long reauthIntervalMs) {
        return forRenewal(
                caseName,
                caseStartNanos,
                initialToken,
                reauthIntervalMs,
                System.nanoTime(),
                System.currentTimeMillis());
    }

    static ReauthDeadline forRenewal(
            String caseName,
            long caseStartNanos,
            TokenAuditEvent initialToken,
            long reauthIntervalMs,
            long nowNanos,
            long nowEpochMs) {
        if (initialToken.ttlSeconds() != EXPECTED_TOKEN_TTL_SECONDS) {
            throw new IllegalArgumentException(
                    "The observed access-token TTL must be 300 seconds for this integration run");
        }
        if (reauthIntervalMs <= 0
                || reauthIntervalMs >= TimeUnit.SECONDS.toMillis(initialToken.ttlSeconds())) {
            throw new IllegalArgumentException(
                    "The effective reauthentication interval must be positive and less than the token TTL");
        }

        long scheduledReauthEpochMs =
                TimeUnit.SECONDS.toMillis(initialToken.issuedAtEpochSeconds()) + reauthIntervalMs;
        long expiryGraceDeadlineEpochMs =
                TimeUnit.SECONDS.toMillis(initialToken.expiresAtEpochSeconds()) + POST_EXPIRY_GRACE_MS;
        long expiryGraceRemainingMs = expiryGraceDeadlineEpochMs - nowEpochMs;
        long caseRemainingNanos = caseStartNanos
                + TimeUnit.MILLISECONDS.toNanos(MAX_CASE_WAIT_MS)
                - nowNanos;
        long deadlineNanos = Math.min(
                nowNanos + TimeUnit.MILLISECONDS.toNanos(Math.max(0, expiryGraceRemainingMs)),
                caseStartNanos + TimeUnit.MILLISECONDS.toNanos(MAX_CASE_WAIT_MS));
        if (deadlineNanos <= nowNanos || caseRemainingNanos <= 0) {
            throw new IllegalStateException(
                    "Reauthentication deadline has already elapsed; inspect token exp and effective broker interval");
        }

        long effectiveDeadlineEpochMs =
                nowEpochMs + TimeUnit.NANOSECONDS.toMillis(deadlineNanos - nowNanos);
        return new ReauthDeadline(
                caseName, deadlineNanos, effectiveDeadlineEpochMs, scheduledReauthEpochMs);
    }

    private static String required(Map<String, String> fields, String name) throws IOException {
        String value = fields.get(name);
        if (value == null || value.isBlank()) {
            throw new IOException("Token audit is missing " + name);
        }
        return value;
    }

    private static int parseInt(Map<String, String> fields, String name) throws IOException {
        try {
            return Integer.parseInt(required(fields, name));
        } catch (NumberFormatException exception) {
            throw new IOException("Token audit contains an invalid " + name, exception);
        }
    }

    private static boolean parseBoolean(Map<String, String> fields, String name) throws IOException {
        String value = required(fields, name);
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IOException("Token audit contains an invalid " + name);
        }
        return Boolean.parseBoolean(value);
    }

    private static long optionalLong(Map<String, String> fields, String name) throws IOException {
        String value = fields.get(name);
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IOException("Token audit contains an invalid " + name, exception);
        }
    }

    record TokenAuditEvent(
            String requestId,
            String clientId,
            int status,
            boolean rejectionArmed,
            boolean rejectionApplied,
            long issuedAtEpochSeconds,
            long expiresAtEpochSeconds) {
        long ttlSeconds() {
            return expiresAtEpochSeconds - issuedAtEpochSeconds;
        }
    }

    record ReauthDeadline(
            String caseName, long deadlineNanos, long deadlineEpochMs, long scheduledReauthEpochMs) {
        long remainingMs() {
            return Math.max(
                    0,
                    TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
        }
    }
}

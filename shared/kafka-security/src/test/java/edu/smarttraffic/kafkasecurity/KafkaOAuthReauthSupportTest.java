package edu.smarttraffic.kafkasecurity;

import static edu.smarttraffic.kafkasecurity.KafkaOAuthReauthSupport.EXPECTED_REAUTH_INTERVAL_MS;
import static edu.smarttraffic.kafkasecurity.KafkaOAuthReauthSupport.POST_REAUTH_RESERVE_MS;
import static edu.smarttraffic.kafkasecurity.KafkaOAuthReauthSupport.TOPIC_CHECK_TIMEOUT_MS;
import static edu.smarttraffic.kafkasecurity.KafkaOAuthReauthSupport.eventsSince;
import static edu.smarttraffic.kafkasecurity.KafkaOAuthReauthSupport.forRenewal;
import static edu.smarttraffic.kafkasecurity.KafkaOAuthReauthSupport.readTokenAudit;
import static edu.smarttraffic.kafkasecurity.KafkaOAuthReauthSupport.requestIds;
import static edu.smarttraffic.kafkasecurity.KafkaOAuthReauthSupport.verificationDeadlineNanos;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.smarttraffic.kafkasecurity.KafkaOAuthReauthSupport.ReauthDeadline;
import edu.smarttraffic.kafkasecurity.KafkaOAuthReauthSupport.TokenAuditEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KafkaOAuthReauthSupportTest {
    @TempDir
    Path tempDirectory;

    @Test
    void correlatesOnlyNewRequestsByUniqueIdAndReadsMarkerState() throws Exception {
        Path audit = tempDirectory.resolve("token-audit.tsv");
        String priorId = UUID.randomUUID().toString();
        String newId = UUID.randomUUID().toString();
        Files.writeString(
                audit,
                auditLine(priorId, 17, false, false) + System.lineSeparator()
                        + auditLine(newId, 17, true, true) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        Set<String> knownRequests = Set.of(priorId);
        List<TokenAuditEvent> newEvents = eventsSince(audit, knownRequests, "simulator-client");

        assertEquals(1, newEvents.size());
        assertEquals(newId, newEvents.get(0).requestId());
        assertTrue(newEvents.get(0).rejectionArmed());
        assertTrue(newEvents.get(0).rejectionApplied());
        assertEquals(Set.of(priorId, newId), requestIds(audit));
    }

    @Test
    void rejectsDuplicateRequestIdsInAudit() throws Exception {
        Path audit = tempDirectory.resolve("duplicate-audit.tsv");
        String requestId = UUID.randomUUID().toString();
        String line = auditLine(requestId, 1, false, false);
        Files.writeString(audit, line + System.lineSeparator() + line + System.lineSeparator());

        assertThrows(IOException.class, () -> readTokenAudit(audit));
    }

    @Test
    void renewalDeadlineIncludesSixtySecondsAfterExpiry() {
        TokenAuditEvent token = tokenIssuedAt(1_000, 1_300);
        long caseStartNanos = 0;
        long nowNanos = 10_000_000_000L;
        long nowEpochMs = 1_240_000L;

        ReauthDeadline deadline = forRenewal(
                "positive",
                caseStartNanos,
                token,
                EXPECTED_REAUTH_INTERVAL_MS,
                POST_REAUTH_RESERVE_MS,
                nowNanos,
                nowEpochMs);

        assertEquals(1_240_000L, deadline.scheduledReauthEpochMs());
        assertEquals(1_360_000L, deadline.deadlineEpochMs());
        assertEquals(nowNanos + 120_000_000_000L, deadline.deadlineNanos());
    }

    @Test
    void renewalDeadlineReservesPostSendAndTopicVerificationWithinTheSixMinuteLimit() {
        TokenAuditEvent token = tokenIssuedAt(1_000, 1_300);
        long caseStartNanos = 0;
        long nowNanos = 30_000_000_000L;
        long nowEpochMs = 1_000_000L;

        ReauthDeadline deadline = forRenewal(
                "negative",
                caseStartNanos,
                token,
                EXPECTED_REAUTH_INTERVAL_MS,
                POST_REAUTH_RESERVE_MS,
                nowNanos,
                nowEpochMs);

        long caseDeadlineNanos = TimeUnit.MINUTES.toNanos(6);
        assertEquals(caseDeadlineNanos - TimeUnit.MILLISECONDS.toNanos(POST_REAUTH_RESERVE_MS),
                deadline.deadlineNanos());
        assertEquals(1_295_000L, deadline.deadlineEpochMs());
        assertEquals(POST_REAUTH_RESERVE_MS, deadline.reservedPostReauthMs());
    }

    @Test
    void topicVerificationGetsItsOwnBudgetWithinTheCaseDeadline() throws Exception {
        long nowNanos = TimeUnit.SECONDS.toNanos(325);
        long caseDeadlineNanos = TimeUnit.MINUTES.toNanos(6);

        long verificationDeadline =
                verificationDeadlineNanos(caseDeadlineNanos, nowNanos, TOPIC_CHECK_TIMEOUT_MS);

        assertEquals(
                nowNanos + TimeUnit.MILLISECONDS.toNanos(TOPIC_CHECK_TIMEOUT_MS),
                verificationDeadline);
        assertTrue(verificationDeadline < caseDeadlineNanos);
        assertEquals(
                caseDeadlineNanos,
                verificationDeadlineNanos(
                        caseDeadlineNanos,
                        caseDeadlineNanos - TimeUnit.SECONDS.toNanos(5),
                        TOPIC_CHECK_TIMEOUT_MS));
    }

    @Test
    void topicVerificationRejectsAnAlreadyExpiredCaseDeadline() {
        long nowNanos = TimeUnit.SECONDS.toNanos(326);
        long expiredCaseDeadlineNanos = TimeUnit.SECONDS.toNanos(325);

        assertThrows(
                TimeoutException.class,
                () -> verificationDeadlineNanos(
                        expiredCaseDeadlineNanos, nowNanos, TOPIC_CHECK_TIMEOUT_MS));
    }

    @Test
    void rejectsAnIntervalThatCannotReauthenticateBeforeExpiry() {
        TokenAuditEvent token = tokenIssuedAt(1_000, 1_300);

        assertThrows(
                IllegalArgumentException.class,
                () -> forRenewal(
                        "positive", 0, token, 300_000, POST_REAUTH_RESERVE_MS, 0, 1_000_000));
    }

    private static String auditLine(
            String requestId, int reusableSequence, boolean rejectionArmed, boolean rejectionApplied) {
        return "requestId=" + requestId
                + "\ttime=2026-11-13T21:00:00Z"
                + "\tclientId=simulator-client"
                + "\tstatus=401"
                + "\trejectionArmed=" + rejectionArmed
                + "\trejectionApplied=" + rejectionApplied
                + "\tsequence=" + reusableSequence;
    }

    private static TokenAuditEvent tokenIssuedAt(long issuedAt, long expiresAt) {
        return new TokenAuditEvent(
                UUID.randomUUID().toString(),
                "simulator-client",
                200,
                false,
                false,
                issuedAt,
                expiresAt);
    }
}

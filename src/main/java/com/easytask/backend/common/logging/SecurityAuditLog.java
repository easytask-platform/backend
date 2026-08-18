package com.easytask.backend.common.logging;

import com.easytask.backend.audit.AuditEventService;
import com.easytask.backend.audit.AuditEventType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Dedicated audit trail for security-relevant events. Uses its own logger
 * name so the events can be routed/retained separately from application logs.
 * Never logs passwords or tokens.
 *
 * <p>P4-11 (D39): sensitive admin actions and auth-critical events are
 * <em>dual-written</em> — the SLF4J line stays, and a {@code
 * security_audit_events} row is persisted via {@link AuditEventService} so the
 * admin audit screen can query them. High-volume events (login, rate limits)
 * remain log-only.
 */
@Component
@RequiredArgsConstructor
public class SecurityAuditLog {

    private static final Logger log = LoggerFactory.getLogger("SECURITY_AUDIT");

    private final AuditEventService auditEventService;

    public void loginSucceeded(UUID userId, String email) {
        log.info("LOGIN_SUCCESS user={} email={}", userId, email);
    }

    public void loginFailed(String email, String reason) {
        log.warn("LOGIN_FAILURE email={} reason={}", email, reason);
    }

    public void refreshRejected(String reason) {
        log.warn("REFRESH_REJECTED reason={}", reason);
    }

    public void refreshTokenReuseDetected(UUID userId) {
        log.warn("REFRESH_TOKEN_REUSE user={} — all sessions revoked", userId);
        auditEventService.recordForUser(AuditEventType.REFRESH_TOKEN_REUSE, userId,
                "Refresh token reuse detected — all sessions revoked");
    }

    public void loggedOut(UUID userId) {
        log.info("LOGOUT user={}", userId);
    }

    public void passwordChanged(UUID userId) {
        log.info("PASSWORD_CHANGED user={}", userId);
    }

    public void passwordResetRequested(String email, boolean knownAccount) {
        log.info("PASSWORD_RESET_REQUESTED email={} known={}", email, knownAccount);
    }

    public void passwordResetCompleted(UUID userId) {
        log.info("PASSWORD_RESET_COMPLETED user={}", userId);
        auditEventService.recordForUser(AuditEventType.PASSWORD_RESET_COMPLETED, userId,
                "Password reset completed");
    }

    public void adminResetPassword(UUID actorId, UUID targetUserId) {
        log.info("ADMIN_PASSWORD_RESET actor={} target={}", actorId, targetUserId);
        auditEventService.recordActorOnTarget(AuditEventType.ADMIN_PASSWORD_RESET, actorId, targetUserId,
                "Admin reset the user's password");
    }

    public void roleChanged(UUID actorId, UUID targetUserId, String newRole) {
        log.info("ROLE_CHANGED actor={} target={} newRole={}", actorId, targetUserId, newRole);
        auditEventService.recordActorOnTarget(AuditEventType.ROLE_CHANGED, actorId, targetUserId,
                "Role changed to " + newRole);
    }

    public void userDeactivated(UUID actorId, UUID targetUserId) {
        log.info("USER_DEACTIVATED actor={} target={}", actorId, targetUserId);
        auditEventService.recordActorOnTarget(AuditEventType.USER_DEACTIVATED, actorId, targetUserId,
                "User deactivated");
    }

    public void rateLimited(String path, String key) {
        log.warn("RATE_LIMITED path={} key={}", path, key);
    }
}

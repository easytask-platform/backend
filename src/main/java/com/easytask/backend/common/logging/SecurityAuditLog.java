package com.easytask.backend.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Dedicated audit trail for security-relevant events. Uses its own logger
 * name so the events can be routed/retained separately from application logs.
 * Never logs passwords or tokens.
 */
@Component
public class SecurityAuditLog {

    private static final Logger log = LoggerFactory.getLogger("SECURITY_AUDIT");

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
    }

    public void adminResetPassword(UUID actorId, UUID targetUserId) {
        log.info("ADMIN_PASSWORD_RESET actor={} target={}", actorId, targetUserId);
    }

    public void roleChanged(UUID actorId, UUID targetUserId, String newRole) {
        log.info("ROLE_CHANGED actor={} target={} newRole={}", actorId, targetUserId, newRole);
    }

    public void userDeactivated(UUID actorId, UUID targetUserId) {
        log.info("USER_DEACTIVATED actor={} target={}", actorId, targetUserId);
    }

    public void rateLimited(String path, String key) {
        log.warn("RATE_LIMITED path={} key={}", path, key);
    }
}

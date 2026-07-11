package com.easytask.backend.auth;

import com.easytask.backend.common.logging.SecurityAuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Revokes every session for a user whose already-rotated refresh token was
 * replayed. Runs in its own transaction so the revocation commits even though
 * the surrounding rotate() then throws (and rolls back) — a separate bean
 * because a self-invoked {@code REQUIRES_NEW} would not start a new tx.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenReuseHandler {

    private final RefreshTokenRepository refreshTokenRepository;
    private final SecurityAuditLog audit;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllOnReuse(UUID userId) {
        refreshTokenRepository.revokeAllForUser(userId, Instant.now());
        audit.refreshTokenReuseDetected(userId);
    }
}

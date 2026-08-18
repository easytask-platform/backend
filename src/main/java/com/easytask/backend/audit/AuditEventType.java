package com.easytask.backend.audit;

/**
 * The persisted subset of security events (P4-11, D39). High-volume events
 * (login success/failure, rate limits) are intentionally excluded — they stay
 * SLF4J-only in {@link com.easytask.backend.common.logging.SecurityAuditLog}.
 */
public enum AuditEventType {
    ROLE_CHANGED,
    USER_DEACTIVATED,
    ADMIN_PASSWORD_RESET,
    PASSWORD_RESET_COMPLETED,
    REFRESH_TOKEN_REUSE
}

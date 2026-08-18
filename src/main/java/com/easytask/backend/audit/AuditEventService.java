package com.easytask.backend.audit;

import com.easytask.backend.organization.OrganizationRepository;
import com.easytask.backend.user.AppUser;
import com.easytask.backend.user.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Persistence path for security audit events (P4-11, D39). Called by
 * {@link com.easytask.backend.common.logging.SecurityAuditLog} so the existing
 * call sites dual-write a {@code security_audit_events} row in addition to the
 * SLF4J line — no call site needs to change. Organization is derived from the
 * actor (or the target when there is no actor); never trusted from a request.
 *
 * <p>Writes participate in the caller's transaction, matching the synchronous
 * activity-log/notification convention (audit rows commit with the action that
 * produced them).
 */
@Service
@RequiredArgsConstructor
public class AuditEventService {

    private final SecurityAuditEventRepository repository;
    private final AppUserRepository userRepository;
    private final OrganizationRepository organizationRepository;

    /** Actor acted on a target user (ROLE_CHANGED, USER_DEACTIVATED, ADMIN_PASSWORD_RESET). */
    public void recordActorOnTarget(AuditEventType type, UUID actorId, UUID targetUserId, String detail) {
        AppUser actor = actorId == null ? null : userRepository.findById(actorId).orElse(null);
        AppUser target = targetUserId == null ? null : userRepository.findById(targetUserId).orElse(null);
        UUID organizationId = organizationOf(actor, target);
        if (organizationId == null) {
            return; // no resolvable org (e.g. both users vanished) — the SLF4J line still recorded it
        }
        save(type, organizationId, actor, target, detail);
    }

    /** Self-service or system event with only a subject user (PASSWORD_RESET_COMPLETED, REFRESH_TOKEN_REUSE). */
    public void recordForUser(AuditEventType type, UUID userId, String detail) {
        AppUser user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user == null) {
            return;
        }
        save(type, user.getOrganization().getId(), null, user, detail);
    }

    private void save(AuditEventType type, UUID organizationId, AppUser actor, AppUser target, String detail) {
        repository.save(SecurityAuditEvent.builder()
                .organization(organizationRepository.getReferenceById(organizationId))
                .actor(actor)
                .targetUser(target)
                .eventType(type.name())
                .detail(truncate(detail))
                .build());
    }

    private static UUID organizationOf(AppUser actor, AppUser target) {
        if (actor != null) {
            return actor.getOrganization().getId();
        }
        if (target != null) {
            return target.getOrganization().getId();
        }
        return null;
    }

    private static String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= 300 ? detail : detail.substring(0, 300);
    }
}

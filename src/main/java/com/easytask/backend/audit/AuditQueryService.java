package com.easytask.backend.audit;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.PageResponse;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** P4-11 (D39): org-scoped, newest-first audit event feed for the admin screen. */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private final SecurityAuditEventRepository repository;

    @Transactional(readOnly = true)
    public PageResponse<AuditEventResponse> list(AuthenticatedUser principal, String eventType, UUID actorId,
                                                 LocalDate from, LocalDate to, Pageable pageable) {
        Page<SecurityAuditEvent> page = repository.findAll(
                specification(principal.organizationId(), eventType, actorId, from, to), pageable);
        return PageResponse.from(page, AuditEventResponse::from);
    }

    private Specification<SecurityAuditEvent> specification(UUID organizationId, String eventType, UUID actorId,
                                                            LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("organization").get("id"), organizationId));
            if (eventType != null && !eventType.isBlank()) {
                predicates.add(cb.equal(root.get("eventType"), eventType));
            }
            if (actorId != null) {
                predicates.add(cb.equal(root.get("actor").get("id"), actorId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"),
                        from.atStartOfDay(ZoneOffset.UTC).toInstant()));
            }
            if (to != null) {
                // inclusive day range: everything before the start of the day after `to`
                predicates.add(cb.lessThan(root.get("createdAt"),
                        to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}

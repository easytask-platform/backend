package com.easytask.backend.recurring;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecurringTaskRuleRepository
        extends JpaRepository<RecurringTaskRule, UUID>, JpaSpecificationExecutor<RecurringTaskRule> {

    Optional<RecurringTaskRule> findByIdAndProjectOrganizationId(UUID id, UUID organizationId);

    Page<RecurringTaskRule> findAllByProjectOrganizationId(UUID organizationId, Pageable pageable);

    List<RecurringTaskRule> findAllByActiveTrueAndNextRunAtLessThanEqual(Instant now);
}

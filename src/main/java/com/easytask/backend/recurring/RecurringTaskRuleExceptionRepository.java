package com.easytask.backend.recurring;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface RecurringTaskRuleExceptionRepository
        extends JpaRepository<RecurringTaskRuleException, UUID> {

    List<RecurringTaskRuleException> findAllByRuleId(UUID ruleId);

    boolean existsByRuleIdAndExceptionDate(UUID ruleId, LocalDate exceptionDate);

    Optional<RecurringTaskRuleException> findByRuleIdAndExceptionDate(UUID ruleId, LocalDate exceptionDate);

    /** Batch: exception dates for a rule (used to flag computed occurrences). */
    default Set<LocalDate> exceptionDates(UUID ruleId) {
        return findAllByRuleId(ruleId).stream()
                .map(RecurringTaskRuleException::getExceptionDate)
                .collect(java.util.stream.Collectors.toSet());
    }
}

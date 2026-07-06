package com.easytask.backend.recurring;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RecurringTaskRuleAssigneeRepository extends JpaRepository<RecurringTaskRuleAssignee, UUID> {

    List<RecurringTaskRuleAssignee> findAllByRuleId(UUID ruleId);

    void deleteAllByRuleId(UUID ruleId);
}

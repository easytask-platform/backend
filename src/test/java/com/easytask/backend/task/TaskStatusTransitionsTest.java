package com.easytask.backend.task;

import com.easytask.backend.user.UserRole;
import org.junit.jupiter.api.Test;

import static com.easytask.backend.task.TaskStatus.APPROVED;
import static com.easytask.backend.task.TaskStatus.CANCELLED;
import static com.easytask.backend.task.TaskStatus.IN_PROGRESS;
import static com.easytask.backend.task.TaskStatus.IN_REVIEW;
import static com.easytask.backend.task.TaskStatus.REOPENED;
import static com.easytask.backend.task.TaskStatus.TO_DO;
import static com.easytask.backend.user.UserRole.EMPLOYEE;
import static com.easytask.backend.user.UserRole.MANAGER;
import static com.easytask.backend.user.UserRole.ORGANIZATION_ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

class TaskStatusTransitionsTest {

    @Test
    void employeeAllowedTransitions() {
        assertThat(TaskStatusTransitions.isAllowed(EMPLOYEE, TO_DO, IN_PROGRESS)).isTrue();
        assertThat(TaskStatusTransitions.isAllowed(EMPLOYEE, IN_PROGRESS, IN_REVIEW)).isTrue();
        assertThat(TaskStatusTransitions.isAllowed(EMPLOYEE, REOPENED, IN_PROGRESS)).isTrue();
        assertThat(TaskStatusTransitions.isAllowed(EMPLOYEE, REOPENED, IN_REVIEW)).isTrue();
    }

    @Test
    void employeeForbiddenTransitions() {
        assertThat(TaskStatusTransitions.isAllowed(EMPLOYEE, IN_REVIEW, APPROVED)).isFalse();
        assertThat(TaskStatusTransitions.isAllowed(EMPLOYEE, IN_REVIEW, REOPENED)).isFalse();
        assertThat(TaskStatusTransitions.isAllowed(EMPLOYEE, TO_DO, CANCELLED)).isFalse();
        assertThat(TaskStatusTransitions.isAllowed(EMPLOYEE, TO_DO, IN_REVIEW)).isFalse();
        assertThat(TaskStatusTransitions.isAllowed(EMPLOYEE, IN_PROGRESS, TO_DO)).isFalse();
        assertThat(TaskStatusTransitions.isAllowed(EMPLOYEE, APPROVED, REOPENED)).isFalse();
    }

    @Test
    void managerAndAdminAllowedTransitions() {
        for (UserRole role : new UserRole[]{MANAGER, ORGANIZATION_ADMIN}) {
            assertThat(TaskStatusTransitions.isAllowed(role, IN_REVIEW, APPROVED)).isTrue();
            assertThat(TaskStatusTransitions.isAllowed(role, IN_REVIEW, REOPENED)).isTrue();
            assertThat(TaskStatusTransitions.isAllowed(role, TO_DO, CANCELLED)).isTrue();
            assertThat(TaskStatusTransitions.isAllowed(role, IN_PROGRESS, CANCELLED)).isTrue();
            assertThat(TaskStatusTransitions.isAllowed(role, IN_REVIEW, CANCELLED)).isTrue();
            assertThat(TaskStatusTransitions.isAllowed(role, REOPENED, CANCELLED)).isTrue();
        }
    }

    @Test
    void managerAndAdminForbiddenTransitions() {
        for (UserRole role : new UserRole[]{MANAGER, ORGANIZATION_ADMIN}) {
            assertThat(TaskStatusTransitions.isAllowed(role, APPROVED, CANCELLED)).isFalse();
            assertThat(TaskStatusTransitions.isAllowed(role, CANCELLED, CANCELLED)).isFalse();
            assertThat(TaskStatusTransitions.isAllowed(role, TO_DO, IN_PROGRESS)).isFalse();
            assertThat(TaskStatusTransitions.isAllowed(role, TO_DO, APPROVED)).isFalse();
            assertThat(TaskStatusTransitions.isAllowed(role, APPROVED, REOPENED)).isFalse();
        }
    }
}

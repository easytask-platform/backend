package com.easytask.backend.task;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.easytask.backend.task.TaskStatus.APPROVED;
import static com.easytask.backend.task.TaskStatus.CANCELLED;
import static com.easytask.backend.task.TaskStatus.IN_PROGRESS;
import static com.easytask.backend.task.TaskStatus.IN_REVIEW;
import static com.easytask.backend.task.TaskStatus.REOPENED;
import static com.easytask.backend.task.TaskStatus.TO_DO;
import static org.assertj.core.api.Assertions.assertThat;

class TaskStatusTransitionsTest {

    /** The system Employee role's permission set. */
    private static final Set<String> EXECUTE = Set.of("task:execute");

    /**
     * Review + cancel WITHOUT manage — a partial set used to exercise those two
     * branches in isolation. The real Manager/Admin roles ALSO hold task:manage,
     * which grants full any→any override (see {@link #managePermissionAllowsAnyTransition}).
     */
    private static final Set<String> REVIEW_AND_CANCEL = Set.of("task:review", "task:cancel");

    @Test
    void executePermissionAllowsEmployeeTransitions() {
        assertThat(TaskStatusTransitions.isAllowed(EXECUTE, TO_DO, IN_PROGRESS)).isTrue();
        assertThat(TaskStatusTransitions.isAllowed(EXECUTE, IN_PROGRESS, IN_REVIEW)).isTrue();
        assertThat(TaskStatusTransitions.isAllowed(EXECUTE, REOPENED, IN_PROGRESS)).isTrue();
        assertThat(TaskStatusTransitions.isAllowed(EXECUTE, REOPENED, IN_REVIEW)).isTrue();
    }

    @Test
    void executePermissionForbidsReviewAndCancel() {
        assertThat(TaskStatusTransitions.isAllowed(EXECUTE, IN_REVIEW, APPROVED)).isFalse();
        assertThat(TaskStatusTransitions.isAllowed(EXECUTE, IN_REVIEW, REOPENED)).isFalse();
        assertThat(TaskStatusTransitions.isAllowed(EXECUTE, TO_DO, CANCELLED)).isFalse();
        assertThat(TaskStatusTransitions.isAllowed(EXECUTE, TO_DO, IN_REVIEW)).isFalse();
        assertThat(TaskStatusTransitions.isAllowed(EXECUTE, IN_PROGRESS, TO_DO)).isFalse();
        assertThat(TaskStatusTransitions.isAllowed(EXECUTE, APPROVED, REOPENED)).isFalse();
    }

    @Test
    void reviewAndCancelPermissionsAllowManagerTransitions() {
        assertThat(TaskStatusTransitions.isAllowed(REVIEW_AND_CANCEL, IN_REVIEW, APPROVED)).isTrue();
        assertThat(TaskStatusTransitions.isAllowed(REVIEW_AND_CANCEL, IN_REVIEW, REOPENED)).isTrue();
        assertThat(TaskStatusTransitions.isAllowed(REVIEW_AND_CANCEL, TO_DO, CANCELLED)).isTrue();
        assertThat(TaskStatusTransitions.isAllowed(REVIEW_AND_CANCEL, IN_PROGRESS, CANCELLED)).isTrue();
        assertThat(TaskStatusTransitions.isAllowed(REVIEW_AND_CANCEL, IN_REVIEW, CANCELLED)).isTrue();
        assertThat(TaskStatusTransitions.isAllowed(REVIEW_AND_CANCEL, REOPENED, CANCELLED)).isTrue();
    }

    @Test
    void reviewAndCancelPermissionsForbidOtherTransitions() {
        assertThat(TaskStatusTransitions.isAllowed(REVIEW_AND_CANCEL, APPROVED, CANCELLED)).isFalse();
        assertThat(TaskStatusTransitions.isAllowed(REVIEW_AND_CANCEL, CANCELLED, CANCELLED)).isFalse();
        assertThat(TaskStatusTransitions.isAllowed(REVIEW_AND_CANCEL, TO_DO, IN_PROGRESS)).isFalse();
        assertThat(TaskStatusTransitions.isAllowed(REVIEW_AND_CANCEL, TO_DO, APPROVED)).isFalse();
        assertThat(TaskStatusTransitions.isAllowed(REVIEW_AND_CANCEL, APPROVED, REOPENED)).isFalse();
    }

    @Test
    void reviewWithoutCancelCannotCancel() {
        Set<String> reviewOnly = Set.of("task:review");
        assertThat(TaskStatusTransitions.isAllowed(reviewOnly, IN_REVIEW, APPROVED)).isTrue();
        assertThat(TaskStatusTransitions.isAllowed(reviewOnly, TO_DO, CANCELLED)).isFalse();
    }

    @Test
    void combinedPermissionsUnionTheMatrices() {
        Set<String> all = Set.of("task:execute", "task:review", "task:cancel");
        assertThat(TaskStatusTransitions.isAllowed(all, TO_DO, IN_PROGRESS)).isTrue();
        assertThat(TaskStatusTransitions.isAllowed(all, IN_REVIEW, APPROVED)).isTrue();
        assertThat(TaskStatusTransitions.isAllowed(all, IN_PROGRESS, CANCELLED)).isTrue();
        assertThat(TaskStatusTransitions.isAllowed(all, APPROVED, CANCELLED)).isFalse();
    }

    @Test
    void managePermissionAllowsAnyTransition() {
        Set<String> manage = Set.of("task:manage");
        // forward, skipping the review gate, backward, and out of terminal states
        assertThat(TaskStatusTransitions.isAllowed(manage, TO_DO, APPROVED)).isTrue();
        assertThat(TaskStatusTransitions.isAllowed(manage, APPROVED, IN_PROGRESS)).isTrue();
        assertThat(TaskStatusTransitions.isAllowed(manage, CANCELLED, TO_DO)).isTrue();
        assertThat(TaskStatusTransitions.isAllowed(manage, IN_REVIEW, TO_DO)).isTrue();
        // a no-op (same status) is still not a transition
        assertThat(TaskStatusTransitions.isAllowed(manage, APPROVED, APPROVED)).isFalse();
    }

    @Test
    void noPermissionsMeansNoTransitions() {
        assertThat(TaskStatusTransitions.isAllowed(Set.of(), TO_DO, IN_PROGRESS)).isFalse();
        assertThat(TaskStatusTransitions.isAllowed(Set.of(), IN_REVIEW, APPROVED)).isFalse();
    }
}

package com.easytask.backend.activity;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ItemsResponse;
import com.easytask.backend.common.MembershipRole;
import com.easytask.backend.common.PageResponse;
import com.easytask.backend.project.ProjectMemberRepository;
import com.easytask.backend.role.DataScope;
import com.easytask.backend.task.TaskAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ActivityController {

    private final TaskActivityLogRepository activityLogRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final TaskAccessService taskAccessService;

    @GetMapping("/tasks/{taskId}/activity")
    @Transactional(readOnly = true)
    public ItemsResponse<ActivityLogResponse> list(@AuthenticationPrincipal AuthenticatedUser principal,
                                                   @PathVariable UUID taskId) {
        taskAccessService.getVisibleTask(principal, taskId);
        return new ItemsResponse<>(activityLogRepository.findAllByTaskIdOrderByCreatedAtDesc(taskId).stream()
                .map(ActivityLogResponse::from)
                .toList());
    }

    /**
     * Org-wide recent-activity feed (P3-4/D27), scoped by data scope:
     * ORGANIZATION → everything; MANAGED → tasks in managed projects;
     * ASSIGNED → tasks assigned to the caller.
     */
    @GetMapping("/activity")
    @Transactional(readOnly = true)
    public PageResponse<OrgActivityItemResponse> feed(@AuthenticationPrincipal AuthenticatedUser principal,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<TaskActivityLog> feed;
        if (principal.scope() == DataScope.ORGANIZATION) {
            feed = activityLogRepository.findFeedByOrganization(principal.organizationId(), pageable);
        } else if (principal.scope() == DataScope.MANAGED) {
            List<UUID> managedProjects = projectMemberRepository
                    .findProjectIdsByUserAndRole(principal.id(), MembershipRole.MANAGER);
            feed = managedProjects.isEmpty()
                    ? Page.empty(pageable)
                    : activityLogRepository.findFeedByProjects(managedProjects, pageable);
        } else {
            feed = activityLogRepository.findFeedByAssignee(principal.id(), pageable);
        }
        return PageResponse.from(feed, OrgActivityItemResponse::from);
    }
}

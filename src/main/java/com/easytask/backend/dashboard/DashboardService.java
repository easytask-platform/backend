package com.easytask.backend.dashboard;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.project.ProjectRepository;
import com.easytask.backend.task.Task;
import com.easytask.backend.task.TaskRepository;
import com.easytask.backend.task.TaskStatus;
import com.easytask.backend.team.TeamRepository;
import com.easytask.backend.user.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.easytask.backend.dashboard.ReportService.CLOSED_STATUSES;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final int OVERDUE_LIST_LIMIT = 10;
    private static final int DASHBOARD_LIST_LIMIT = 10;

    private final AppUserRepository userRepository;
    private final TeamRepository teamRepository;
    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final ReportService reportService;

    @Transactional(readOnly = true)
    public AdminDashboardResponse adminDashboard(AuthenticatedUser principal) {
        UUID orgId = principal.organizationId();
        TasksByStatusResponse byStatus = TasksByStatusResponse.from(
                statusMap(taskRepository.countByStatusForOrganization(orgId)));
        List<AdminDashboardResponse.OverdueTaskItem> overdueTasks = taskRepository
                .findOverdueForOrganization(orgId, LocalDate.now(), CLOSED_STATUSES,
                        PageRequest.of(0, OVERDUE_LIST_LIMIT))
                .stream()
                .map(task -> new AdminDashboardResponse.OverdueTaskItem(task.getId(), task.getTitle(),
                        task.getProject().getName(), task.getDueDate(), task.getStatus()))
                .toList();
        return new AdminDashboardResponse(
                userRepository.countByOrganizationId(orgId),
                userRepository.countByOrganizationIdAndActiveTrue(orgId),
                teamRepository.countByOrganizationId(orgId),
                projectRepository.countByOrganizationId(orgId),
                byStatus.total(),
                byStatus,
                overdueTasks);
    }

    @Transactional(readOnly = true)
    public ManagerDashboardResponse managerDashboard(AuthenticatedUser principal) {
        List<UUID> scope = reportService.scopeProjectIds(principal);
        TasksByStatusResponse byStatus;
        long overdueCount;
        if (scope == null) {
            byStatus = TasksByStatusResponse.from(statusMap(
                    taskRepository.countByStatusForOrganization(principal.organizationId())));
            overdueCount = taskRepository.countByProjectOrganizationIdAndDueDateBeforeAndStatusNotIn(
                    principal.organizationId(), LocalDate.now(), CLOSED_STATUSES);
        } else if (scope.isEmpty()) {
            byStatus = TasksByStatusResponse.from(Map.of());
            overdueCount = 0;
        } else {
            Map<TaskStatus, Long> counts = new EnumMap<>(TaskStatus.class);
            for (Object[] row : taskRepository.countByStatusForProjects(scope)) {
                counts.merge((TaskStatus) row[1], (Long) row[2], Long::sum);
            }
            byStatus = TasksByStatusResponse.from(counts);
            overdueCount = taskRepository.countByProjectIdInAndDueDateBeforeAndStatusNotIn(
                    scope, LocalDate.now(), CLOSED_STATUSES);
        }
        var workload = reportService.workload(principal, null, null, null, null,
                PageRequest.of(0, DASHBOARD_LIST_LIMIT));
        var progress = reportService.projectProgress(principal, null, null, null,
                PageRequest.of(0, DASHBOARD_LIST_LIMIT));
        return new ManagerDashboardResponse(
                byStatus.total(),
                byStatus.inReview(),
                overdueCount,
                byStatus,
                workload.items(),
                progress.items());
    }

    private static Map<TaskStatus, Long> statusMap(List<Object[]> rows) {
        Map<TaskStatus, Long> counts = new EnumMap<>(TaskStatus.class);
        for (Object[] row : rows) {
            counts.put((TaskStatus) row[0], (Long) row[1]);
        }
        return counts;
    }
}

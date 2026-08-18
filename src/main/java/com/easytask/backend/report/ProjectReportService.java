package com.easytask.backend.report;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.project.Project;
import com.easytask.backend.project.ProjectMember;
import com.easytask.backend.project.ProjectMemberRepository;
import com.easytask.backend.project.ProjectRepository;
import com.easytask.backend.task.Task;
import com.easytask.backend.task.TaskListItemResponse;
import com.easytask.backend.task.TaskRepository;
import com.easytask.backend.task.TaskService;
import com.easytask.backend.task.TaskStatus;
import com.easytask.backend.timeentry.TimeEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P4-12 (D40): assembles the project report model and renders the single
 * server-side HTML template. {@code format=html} returns the HTML; the caller
 * renders it to PDF via {@link ProjectReportPdfRenderer}.
 */
@Service
@RequiredArgsConstructor
public class ProjectReportService {

    private static final LocalDate MIN_DATE = LocalDate.of(1970, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final TaskRepository taskRepository;
    private final TimeEntryRepository timeEntryRepository;
    private final TaskService taskService;

    public record MemberProgress(String fullName, long assigned, long completed, BigDecimal loggedHours) {
    }

    public record ReportModel(String projectName, ProjectStatusView status, LocalDate startDate,
                              LocalDate dueDate, int progress, long totalTasks, long approvedTasks,
                              Map<TaskStatus, Long> statusCounts, List<MemberProgress> team,
                              List<TaskListItemResponse> tasks, LocalDate generatedOn) {
    }

    /** Simple projection of the project status enum name for the template. */
    public record ProjectStatusView(String name) {
    }

    @Transactional(readOnly = true)
    public ReportModel buildModel(AuthenticatedUser principal, UUID projectId) {
        Project project = projectRepository.findByIdAndOrganizationId(projectId, principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Project not found"));

        List<Task> tasks = taskRepository.findAllByProjectIdOrderByCreatedAtAsc(projectId);
        List<TaskListItemResponse> taskItems = taskService.toListItems(principal, tasks);

        Map<TaskStatus, Long> statusCounts = new EnumMap<>(TaskStatus.class);
        for (Task task : tasks) {
            statusCounts.merge(task.getStatus(), 1L, Long::sum);
        }
        long total = tasks.size();
        long approved = statusCounts.getOrDefault(TaskStatus.APPROVED, 0L);
        long nonCancelled = total - statusCounts.getOrDefault(TaskStatus.CANCELLED, 0L);
        int progress = nonCancelled == 0 ? 0 : Math.round(approved * 100f / nonCancelled);

        // Per-member assigned/completed counts.
        Map<UUID, long[]> counts = new HashMap<>();
        for (Object[] row : taskRepository.assigneeTaskCountsForProject(projectId)) {
            counts.put((UUID) row[0], new long[]{((Number) row[1]).longValue(),
                    ((Number) row[2]).longValue()});
        }
        // Per-member logged hours across the whole project lifetime.
        Map<UUID, BigDecimal> loggedHours = new HashMap<>();
        List<ProjectMember> members = projectMemberRepository.findAllByProjectId(projectId);
        List<UUID> memberIds = members.stream().map(m -> m.getUser().getId()).toList();
        if (!memberIds.isEmpty()) {
            for (Object[] row : timeEntryRepository.loggedHoursByUserForProjects(
                    memberIds, List.of(projectId), MIN_DATE, MAX_DATE)) {
                loggedHours.put((UUID) row[0], (BigDecimal) row[1]);
            }
        }

        // Preserve a stable order by member name; include everyone assigned even if not a formal member.
        Map<UUID, MemberProgress> byUser = new LinkedHashMap<>();
        for (ProjectMember member : members) {
            UUID uid = member.getUser().getId();
            long[] c = counts.getOrDefault(uid, new long[]{0, 0});
            byUser.put(uid, new MemberProgress(member.getUser().getFullName(), c[0], c[1],
                    loggedHours.getOrDefault(uid, BigDecimal.ZERO)));
        }

        List<MemberProgress> team = new ArrayList<>(byUser.values());
        team.sort((a, b) -> a.fullName().compareToIgnoreCase(b.fullName()));

        return new ReportModel(project.getName(), new ProjectStatusView(project.getStatus().name()),
                project.getStartDate(), project.getDueDate(), progress, total, approved,
                statusCounts, team, taskItems, LocalDate.now());
    }
}

package com.easytask.backend.dashboard;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.MembershipRole;
import com.easytask.backend.role.DataScope;
import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.common.PageResponse;
import com.easytask.backend.project.Project;
import com.easytask.backend.project.ProjectMemberRepository;
import com.easytask.backend.project.ProjectRepository;
import com.easytask.backend.task.TaskAssignmentRepository;
import com.easytask.backend.task.TaskRepository;
import com.easytask.backend.task.TaskStatus;
import com.easytask.backend.team.TeamMemberRepository;
import com.easytask.backend.team.TeamRepository;
import com.easytask.backend.timeentry.TimeEntryRepository;
import com.easytask.backend.user.AppUser;
import com.easytask.backend.user.AppUserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReportService {

    static final List<TaskStatus> CLOSED_STATUSES = List.of(TaskStatus.APPROVED, TaskStatus.CANCELLED);
    private static final LocalDate MIN_DATE = LocalDate.of(1970, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);

    private final AppUserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TaskRepository taskRepository;
    private final TaskAssignmentRepository taskAssignmentRepository;
    private final TimeEntryRepository timeEntryRepository;

    /** Managed project ids for managers; {@code null} means "whole organization" (admin). */
    List<UUID> scopeProjectIds(AuthenticatedUser principal) {
        if (principal.scope() == DataScope.ORGANIZATION) {
            return null;
        }
        return projectMemberRepository.findProjectIdsByUserAndRole(principal.id(), MembershipRole.MANAGER);
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkloadItemResponse> workload(AuthenticatedUser principal, UUID teamId, UUID projectId,
                                                       LocalDate from, LocalDate to, Pageable pageable) {
        List<UUID> scope = resolveProjectScope(principal, projectId);
        if (scope != null && scope.isEmpty()) {
            return emptyPage(pageable);
        }
        Set<UUID> visibleUserIds = visibleUserIdsOrNull(principal);
        Set<UUID> teamUserIds = null;
        if (teamId != null) {
            teamRepository.findByIdAndOrganizationId(teamId, principal.organizationId())
                    .orElseThrow(() -> new NotFoundException("Team not found"));
            teamUserIds = new HashSet<>();
            for (var member : teamMemberRepository.findAllByTeamId(teamId)) {
                teamUserIds.add(member.getUser().getId());
            }
            if (teamUserIds.isEmpty()) {
                return emptyPage(pageable);
            }
        }

        Page<AppUser> users = userRepository.findAll(
                userSpecification(principal.organizationId(), visibleUserIds, teamUserIds), pageable);
        List<UUID> userIds = users.getContent().stream().map(AppUser::getId).toList();
        if (userIds.isEmpty()) {
            return PageResponse.from(users.map(user -> null));
        }

        LocalDate today = LocalDate.now();
        Map<UUID, long[]> stats = new HashMap<>();
        List<Object[]> statRows = scope == null
                ? taskAssignmentRepository.workloadStatsForOrganization(userIds, principal.organizationId(),
                        today, CLOSED_STATUSES)
                : taskAssignmentRepository.workloadStatsForProjects(userIds, scope, today, CLOSED_STATUSES);
        for (Object[] row : statRows) {
            stats.put((UUID) row[0], new long[]{
                    ((Number) row[1]).longValue(),
                    ((Number) row[2]).longValue(),
                    ((Number) row[3]).longValue()});
        }
        Map<UUID, BigDecimal> hours = new HashMap<>();
        List<Object[]> hourRows = scope == null
                ? timeEntryRepository.loggedHoursByUserForOrganization(userIds, principal.organizationId(),
                        orDefault(from, MIN_DATE), orDefault(to, MAX_DATE))
                : timeEntryRepository.loggedHoursByUserForProjects(userIds, scope,
                        orDefault(from, MIN_DATE), orDefault(to, MAX_DATE));
        for (Object[] row : hourRows) {
            hours.put((UUID) row[0], (BigDecimal) row[1]);
        }

        return PageResponse.from(users.map(user -> {
            long[] userStats = stats.getOrDefault(user.getId(), new long[3]);
            return new WorkloadItemResponse(user.getId(), user.getFullName(),
                    userStats[0], userStats[1], userStats[2],
                    hours.getOrDefault(user.getId(), BigDecimal.ZERO));
        }));
    }

    @Transactional(readOnly = true)
    public PageResponse<ProjectProgressItemResponse> projectProgress(AuthenticatedUser principal, UUID projectId,
                                                                     LocalDate from, LocalDate to,
                                                                     Pageable pageable) {
        List<UUID> scope = resolveProjectScope(principal, projectId);
        if (scope != null && scope.isEmpty()) {
            return emptyPage(pageable);
        }
        Page<Project> projects = projectRepository.findAll((root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("organization").get("id"), principal.organizationId()));
            if (scope != null) {
                predicates.add(root.get("id").in(scope));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        }, pageable);

        List<UUID> projectIds = projects.getContent().stream().map(Project::getId).toList();
        if (projectIds.isEmpty()) {
            return PageResponse.from(projects.map(project -> null));
        }
        LocalDate today = LocalDate.now();
        Map<UUID, Map<TaskStatus, Long>> statusCounts = new HashMap<>();
        for (Object[] row : taskRepository.countByStatusForProjects(projectIds)) {
            statusCounts.computeIfAbsent((UUID) row[0], id -> new EnumMap<>(TaskStatus.class))
                    .put((TaskStatus) row[1], (Long) row[2]);
        }
        Map<UUID, Long> overdue = toLongMap(taskRepository.countOverdueByProject(projectIds, today,
                CLOSED_STATUSES));
        Map<UUID, BigDecimal> estimated = toBigDecimalMap(taskRepository.sumEstimatedHoursByProject(projectIds));
        Map<UUID, BigDecimal> logged = toBigDecimalMap(timeEntryRepository.loggedHoursByProject(projectIds,
                orDefault(from, MIN_DATE), orDefault(to, MAX_DATE)));

        return PageResponse.from(projects.map(project -> {
            Map<TaskStatus, Long> counts = statusCounts.getOrDefault(project.getId(), Map.of());
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            long approved = counts.getOrDefault(TaskStatus.APPROVED, 0L);
            long nonCancelled = total - counts.getOrDefault(TaskStatus.CANCELLED, 0L);
            int progress = nonCancelled == 0 ? 0 : Math.round(approved * 100f / nonCancelled);
            return new ProjectProgressItemResponse(project.getId(), project.getName(), progress, total,
                    approved, overdue.getOrDefault(project.getId(), 0L),
                    estimated.getOrDefault(project.getId(), BigDecimal.ZERO),
                    logged.getOrDefault(project.getId(), BigDecimal.ZERO));
        }));
    }

    /** Explicit projectId filter narrows (and validates) the scope; otherwise the role scope applies. */
    private List<UUID> resolveProjectScope(AuthenticatedUser principal, UUID projectId) {
        List<UUID> scope = scopeProjectIds(principal);
        if (projectId == null) {
            return scope;
        }
        projectRepository.findByIdAndOrganizationId(projectId, principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Project not found"));
        if (scope != null && !scope.contains(projectId)) {
            throw new NotFoundException("Project not found");
        }
        return List.of(projectId);
    }

    /** Same manager visibility rule as GET /users: self + members of managed teams/projects. */
    private Set<UUID> visibleUserIdsOrNull(AuthenticatedUser principal) {
        if (principal.scope() == DataScope.ORGANIZATION) {
            return null;
        }
        Set<UUID> ids = new HashSet<>();
        ids.add(principal.id());
        ids.addAll(teamMemberRepository.findUserIdsInTeamsWhereUserHasRole(principal.id(),
                MembershipRole.MANAGER));
        ids.addAll(projectMemberRepository.findUserIdsInProjectsWhereUserHasRole(principal.id(),
                MembershipRole.MANAGER));
        return ids;
    }

    private Specification<AppUser> userSpecification(UUID organizationId, Set<UUID> visibleIds,
                                                     Set<UUID> teamUserIds) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("organization").get("id"), organizationId));
            predicates.add(cb.isTrue(root.get("active")));
            if (visibleIds != null) {
                predicates.add(root.get("id").in(visibleIds));
            }
            if (teamUserIds != null) {
                predicates.add(root.get("id").in(teamUserIds));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static <T> PageResponse<T> emptyPage(Pageable pageable) {
        return new PageResponse<>(List.of(), pageable.getPageNumber(), pageable.getPageSize(), 0, 0);
    }

    private static LocalDate orDefault(LocalDate value, LocalDate fallback) {
        return value == null ? fallback : value;
    }

    private static Map<UUID, Long> toLongMap(List<Object[]> rows) {
        Map<UUID, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return map;
    }

    private static Map<UUID, BigDecimal> toBigDecimalMap(List<Object[]> rows) {
        Map<UUID, BigDecimal> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put((UUID) row[0], (BigDecimal) row[1]);
        }
        return map;
    }
}

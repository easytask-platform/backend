package com.easytask.backend.project;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ConflictException;
import com.easytask.backend.role.DataScope;
import com.easytask.backend.common.ItemsResponse;
import com.easytask.backend.common.MembershipRole;
import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.common.PageResponse;
import com.easytask.backend.common.ValidationException;
import com.easytask.backend.organization.OrganizationRepository;
import com.easytask.backend.task.TaskAssignment;
import com.easytask.backend.task.TaskRepository;
import com.easytask.backend.task.TaskStatus;
import com.easytask.backend.user.AppUser;
import com.easytask.backend.user.AppUserRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectAccessService projectAccessService;
    private final TaskRepository taskRepository;
    private final AppUserRepository userRepository;
    private final OrganizationRepository organizationRepository;

    @Transactional(readOnly = true)
    public PageResponse<ProjectResponse> list(AuthenticatedUser principal, String search, ProjectStatus status,
                                              LocalDate dueFrom, LocalDate dueTo, Pageable pageable) {
        Page<Project> page = projectRepository.findAll(
                projectSpecification(principal, search, status, dueFrom, dueTo), pageable);
        List<UUID> ids = page.getContent().stream().map(Project::getId).toList();
        Map<UUID, Long> memberCounts = memberCounts(ids);
        Map<UUID, Map<TaskStatus, Long>> statusCounts = statusCounts(ids);
        return PageResponse.from(page.map(project -> ProjectResponse.from(project,
                progressPercent(statusCounts.getOrDefault(project.getId(), Map.of())),
                memberCounts.getOrDefault(project.getId(), 0L))));
    }

    @Transactional(readOnly = true)
    public ProjectDetailResponse get(AuthenticatedUser principal, UUID projectId) {
        Project project = requireVisibleProject(principal, projectId);
        Map<TaskStatus, Long> counts = statusCounts(List.of(projectId)).getOrDefault(projectId, Map.of());
        return new ProjectDetailResponse(project.getId(), project.getName(), project.getDescription(),
                project.getStatus(), project.getStartDate(), project.getDueDate(),
                progressPercent(counts), projectMemberRepository.countByProjectId(projectId),
                new ProjectDetailResponse.TaskSummary(
                        counts.getOrDefault(TaskStatus.TO_DO, 0L),
                        counts.getOrDefault(TaskStatus.IN_PROGRESS, 0L),
                        counts.getOrDefault(TaskStatus.IN_REVIEW, 0L),
                        counts.getOrDefault(TaskStatus.APPROVED, 0L),
                        counts.getOrDefault(TaskStatus.REOPENED, 0L),
                        counts.getOrDefault(TaskStatus.CANCELLED, 0L)));
    }

    @Transactional
    public ProjectResponse create(AuthenticatedUser principal, CreateProjectRequest request) {
        validateDates(request.startDate(), request.dueDate());
        Project project = projectRepository.save(Project.builder()
                .organization(organizationRepository.getReferenceById(principal.organizationId()))
                .createdBy(userRepository.getReferenceById(principal.id()))
                .name(request.name())
                .description(request.description())
                .status(request.status() == null ? ProjectStatus.PLANNED : request.status())
                .startDate(request.startDate())
                .dueDate(request.dueDate())
                .build());
        long memberCount = 0;
        // a manager must be a managing member of their own project to keep seeing it
        if (principal.scope() != DataScope.ORGANIZATION) {
            projectMemberRepository.save(ProjectMember.builder()
                    .project(project)
                    .user(userRepository.getReferenceById(principal.id()))
                    .projectRole(MembershipRole.MANAGER)
                    .build());
            memberCount = 1;
        }
        return ProjectResponse.from(project, 0, memberCount);
    }

    @Transactional
    public ProjectResponse update(AuthenticatedUser principal, UUID projectId, UpdateProjectRequest request) {
        Project project = requireVisibleProject(principal, projectId);
        requireManagementRights(principal, projectId);
        LocalDate newStart = request.startDate() != null ? request.startDate() : project.getStartDate();
        LocalDate newDue = request.dueDate() != null ? request.dueDate() : project.getDueDate();
        validateDates(newStart, newDue);
        if (request.name() != null) {
            project.setName(request.name());
        }
        if (request.description() != null) {
            project.setDescription(request.description());
        }
        if (request.status() != null) {
            project.setStatus(request.status());
        }
        project.setStartDate(newStart);
        project.setDueDate(newDue);
        Map<TaskStatus, Long> counts = statusCounts(List.of(projectId)).getOrDefault(projectId, Map.of());
        return ProjectResponse.from(project, progressPercent(counts),
                projectMemberRepository.countByProjectId(projectId));
    }

    @Transactional(readOnly = true)
    public ItemsResponse<ProjectMemberResponse> members(AuthenticatedUser principal, UUID projectId) {
        requireVisibleProject(principal, projectId);
        List<ProjectMemberResponse> items = projectMemberRepository.findAllByProjectId(projectId).stream()
                .map(member -> ProjectMemberResponse.from(member.getUser()))
                .toList();
        return new ItemsResponse<>(items);
    }

    @Transactional
    public ProjectMemberResponse addMember(AuthenticatedUser principal, UUID projectId,
                                           AddProjectMemberRequest request) {
        Project project = requireVisibleProject(principal, projectId);
        requireManagementRights(principal, projectId);
        AppUser user = userRepository.findByIdAndOrganizationId(request.userId(), principal.organizationId())
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (projectMemberRepository.existsByProjectIdAndUserId(projectId, user.getId())) {
            throw new ConflictException("User is already a member of this project");
        }
        MembershipRole role = user.getRole().getDataScope() == DataScope.MANAGED
                ? MembershipRole.MANAGER : MembershipRole.MEMBER;
        projectMemberRepository.save(ProjectMember.builder()
                .project(project)
                .user(user)
                .projectRole(role)
                .build());
        return ProjectMemberResponse.from(user);
    }

    @Transactional
    public void removeMember(AuthenticatedUser principal, UUID projectId, UUID userId) {
        requireVisibleProject(principal, projectId);
        requireManagementRights(principal, projectId);
        ProjectMember member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new NotFoundException("Project member not found"));
        projectMemberRepository.delete(member);
    }

    private Project requireVisibleProject(AuthenticatedUser principal, UUID projectId) {
        return projectAccessService.getVisibleProject(principal, projectId);
    }

    private void requireManagementRights(AuthenticatedUser principal, UUID projectId) {
        projectAccessService.requireManagementRights(principal, projectId);
    }

    private void validateDates(LocalDate startDate, LocalDate dueDate) {
        if (startDate != null && dueDate != null && dueDate.isBefore(startDate)) {
            throw new ValidationException("dueDate", "Due date must be on or after the start date");
        }
    }

    private Specification<Project> projectSpecification(AuthenticatedUser principal, String search,
                                                        ProjectStatus status, LocalDate dueFrom, LocalDate dueTo) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("organization").get("id"), principal.organizationId()));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (dueFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("dueDate"), dueFrom));
            }
            if (dueTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("dueDate"), dueTo));
            }
            if (search != null && !search.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("name")),
                        "%" + search.toLowerCase(Locale.ROOT) + "%"));
            }
            if (principal.scope() != DataScope.ORGANIZATION) {
                Subquery<UUID> membership = query.subquery(UUID.class);
                Root<ProjectMember> pm = membership.from(ProjectMember.class);
                membership.select(pm.get("id"))
                        .where(cb.equal(pm.get("project"), root),
                                cb.equal(pm.get("user").get("id"), principal.id()));

                Subquery<UUID> assignment = query.subquery(UUID.class);
                Root<TaskAssignment> ta = assignment.from(TaskAssignment.class);
                assignment.select(ta.get("id"))
                        .where(cb.equal(ta.get("task").get("project"), root),
                                cb.equal(ta.get("assignee").get("id"), principal.id()));

                predicates.add(cb.or(cb.exists(membership), cb.exists(assignment)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Map<UUID, Long> memberCounts(List<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : projectMemberRepository.countMembersByProjectIds(projectIds)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }

    private Map<UUID, Map<TaskStatus, Long>> statusCounts(List<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Map<TaskStatus, Long>> counts = new HashMap<>();
        for (Object[] row : taskRepository.countByStatusForProjects(projectIds)) {
            counts.computeIfAbsent((UUID) row[0], id -> new EnumMap<>(TaskStatus.class))
                    .put((TaskStatus) row[1], (Long) row[2]);
        }
        return counts;
    }

    /** approved / total non-cancelled tasks, rounded; 0 when the project has no countable tasks. */
    private int progressPercent(Map<TaskStatus, Long> counts) {
        long approved = counts.getOrDefault(TaskStatus.APPROVED, 0L);
        long total = counts.entrySet().stream()
                .filter(e -> e.getKey() != TaskStatus.CANCELLED)
                .mapToLong(Map.Entry::getValue)
                .sum();
        return total == 0 ? 0 : Math.round(approved * 100f / total);
    }
}

package com.easytask.backend.project;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ForbiddenException;
import com.easytask.backend.common.MembershipRole;
import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.task.TaskAssignmentRepository;
import com.easytask.backend.user.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Central project scoping rules, shared by the project and task features.
 * Out-of-scope (other org or not visible) is always a 404 per the contract.
 */
@Service
@RequiredArgsConstructor
public class ProjectAccessService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final TaskAssignmentRepository taskAssignmentRepository;

    /** Visible = own org AND (admin, project member, or assignee of a task in the project). */
    public Project getVisibleProject(AuthenticatedUser principal, UUID projectId) {
        Project project = projectRepository.findByIdAndOrganizationId(projectId, principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Project not found"));
        if (!isVisible(principal, projectId)) {
            throw new NotFoundException("Project not found");
        }
        return project;
    }

    public boolean isVisible(AuthenticatedUser principal, UUID projectId) {
        return principal.role() == UserRole.ORGANIZATION_ADMIN
                || projectMemberRepository.existsByProjectIdAndUserId(projectId, principal.id())
                || taskAssignmentRepository.existsByTaskProjectIdAndAssigneeId(projectId, principal.id());
    }

    /** Admins manage every project; managers only those where they hold the MANAGER membership role. */
    public void requireManagementRights(AuthenticatedUser principal, UUID projectId) {
        if (principal.role() == UserRole.ORGANIZATION_ADMIN) {
            return;
        }
        if (principal.role() != UserRole.MANAGER
                || !projectMemberRepository.existsByProjectIdAndUserIdAndProjectRole(
                        projectId, principal.id(), MembershipRole.MANAGER)) {
            throw new ForbiddenException("You do not manage this project");
        }
    }
}

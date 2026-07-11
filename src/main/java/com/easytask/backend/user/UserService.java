package com.easytask.backend.user;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.auth.RefreshTokenRepository;
import com.easytask.backend.common.ConflictException;
import com.easytask.backend.common.MembershipRole;
import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.common.PageResponse;
import com.easytask.backend.common.ValidationException;
import com.easytask.backend.common.logging.SecurityAuditLog;
import com.easytask.backend.organization.OrganizationRepository;
import com.easytask.backend.project.ProjectMemberRepository;
import com.easytask.backend.role.DataScope;
import com.easytask.backend.role.Role;
import com.easytask.backend.role.RoleRepository;
import com.easytask.backend.team.TeamMemberRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final AppUserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityAuditLog audit;

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(AuthenticatedUser principal, String search, String role,
                                           Boolean active, Pageable pageable) {
        Set<UUID> visibleIds = visibleUserIdsOrNull(principal);
        if (visibleIds != null && visibleIds.isEmpty()) {
            return new PageResponse<>(List.of(), pageable.getPageNumber(), pageable.getPageSize(), 0, 0);
        }
        Page<AppUser> page = userRepository.findAll(
                userSpecification(principal.organizationId(), search, role, active, visibleIds), pageable);
        return PageResponse.from(page, UserResponse::from);
    }

    @Transactional(readOnly = true)
    public UserResponse get(AuthenticatedUser principal, UUID userId) {
        AppUser user = userRepository.findByIdAndOrganizationId(userId, principal.organizationId())
                .orElseThrow(() -> new NotFoundException("User not found"));
        Set<UUID> visibleIds = visibleUserIdsOrNull(principal);
        if (visibleIds != null && !visibleIds.contains(userId)) {
            throw new NotFoundException("User not found");
        }
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse create(AuthenticatedUser principal, CreateUserRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new ConflictException("Email is already used");
        }
        Role role = resolveRole(principal, request.roleId(), request.role());
        if (role == null) {
            throw new ValidationException("Either roleId or role is required", java.util.Map.of());
        }
        AppUser user = userRepository.save(AppUser.builder()
                .organization(organizationRepository.getReferenceById(principal.organizationId()))
                .fullName(request.fullName())
                .email(request.email().toLowerCase(Locale.ROOT))
                .passwordHash(passwordEncoder.encode(request.initialPassword()))
                .role(role)
                .build());
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse update(AuthenticatedUser principal, UUID userId, UpdateUserRequest request) {
        AppUser user = userRepository.findByIdAndOrganizationId(userId, principal.organizationId())
                .orElseThrow(() -> new NotFoundException("User not found"));
        Role newRole = resolveRole(principal, request.roleId(), request.role());
        if (newRole != null && !newRole.getId().equals(user.getRole().getId())) {
            if (userId.equals(principal.id())) {
                throw new ConflictException("You cannot change your own role");
            }
            user.setRole(newRole);
            // Old sessions carry stale permission claims; kill them (D12).
            refreshTokenRepository.revokeAllForUser(userId, Instant.now());
            audit.roleChanged(principal.id(), userId, newRole.getName());
        }
        if (request.fullName() != null) {
            user.setFullName(request.fullName());
        }
        return UserResponse.from(user);
    }

    /**
     * B1: admin sets a temporary password for a locked-out user. Own account
     * uses PATCH /me/password (which verifies the current password) instead.
     */
    @Transactional
    public void resetPassword(AuthenticatedUser principal, UUID userId, AdminResetPasswordRequest request) {
        AppUser user = userRepository.findByIdAndOrganizationId(userId, principal.organizationId())
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (userId.equals(principal.id())) {
            throw new ConflictException("Use the change-password endpoint for your own account");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        refreshTokenRepository.revokeAllForUser(userId, Instant.now());
        audit.adminResetPassword(principal.id(), userId);
    }

    @Transactional
    public void deactivate(AuthenticatedUser principal, UUID userId) {
        AppUser user = userRepository.findByIdAndOrganizationId(userId, principal.organizationId())
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (userId.equals(principal.id())) {
            throw new ConflictException("You cannot deactivate your own account");
        }
        if (!user.isActive()) {
            throw new ConflictException("User is already deactivated");
        }
        user.setActive(false);
        audit.userDeactivated(principal.id(), userId);
    }

    /**
     * Accepts {@code roleId} (preferred) or the legacy {@code role} name;
     * returns null when neither is present (meaning: no role specified).
     * The role must belong to the caller's organization — cross-org is a 404.
     */
    private Role resolveRole(AuthenticatedUser principal, UUID roleId, String roleName) {
        if (roleId == null && (roleName == null || roleName.isBlank())) {
            return null;
        }
        if (roleId != null && roleName != null && !roleName.isBlank()) {
            throw new ValidationException("Provide either roleId or role, not both", java.util.Map.of());
        }
        if (roleId != null) {
            return roleRepository.findByIdAndOrganizationId(roleId, principal.organizationId())
                    .orElseThrow(() -> new NotFoundException("Role not found"));
        }
        return roleRepository.findByOrganizationIdAndName(principal.organizationId(), roleName)
                .orElseThrow(() -> new NotFoundException("Role not found"));
    }

    /**
     * ORGANIZATION scope sees everyone (returns {@code null} = no restriction).
     * Other scopes see themselves plus every user in a team or project where
     * they hold the MANAGER membership (the legacy manager rule).
     */
    private Set<UUID> visibleUserIdsOrNull(AuthenticatedUser principal) {
        if (principal.scope() == DataScope.ORGANIZATION) {
            return null;
        }
        Set<UUID> ids = new HashSet<>();
        ids.add(principal.id());
        ids.addAll(teamMemberRepository.findUserIdsInTeamsWhereUserHasRole(principal.id(), MembershipRole.MANAGER));
        ids.addAll(projectMemberRepository.findUserIdsInProjectsWhereUserHasRole(principal.id(), MembershipRole.MANAGER));
        return ids;
    }

    private Specification<AppUser> userSpecification(UUID organizationId, String search, String role,
                                                     Boolean active, Set<UUID> visibleIds) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("organization").get("id"), organizationId));
            if (role != null && !role.isBlank()) {
                predicates.add(cb.equal(root.get("role").get("name"), role));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            if (search != null && !search.isBlank()) {
                String like = "%" + search.toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("fullName")), like),
                        cb.like(cb.lower(root.get("email")), like)));
            }
            if (visibleIds != null) {
                predicates.add(root.get("id").in(visibleIds));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}

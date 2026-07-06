package com.easytask.backend.user;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ConflictException;
import com.easytask.backend.common.MembershipRole;
import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.common.PageResponse;
import com.easytask.backend.organization.OrganizationRepository;
import com.easytask.backend.project.ProjectMemberRepository;
import com.easytask.backend.team.TeamMemberRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(AuthenticatedUser principal, String search, UserRole role,
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
        AppUser user = userRepository.save(AppUser.builder()
                .organization(organizationRepository.getReferenceById(principal.organizationId()))
                .fullName(request.fullName())
                .email(request.email().toLowerCase(Locale.ROOT))
                .passwordHash(passwordEncoder.encode(request.initialPassword()))
                .role(request.role())
                .build());
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse update(AuthenticatedUser principal, UUID userId, UpdateUserRequest request) {
        AppUser user = userRepository.findByIdAndOrganizationId(userId, principal.organizationId())
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (request.role() != null && request.role() != user.getRole() && userId.equals(principal.id())) {
            throw new ConflictException("You cannot change your own role");
        }
        if (request.fullName() != null) {
            user.setFullName(request.fullName());
        }
        if (request.role() != null) {
            user.setRole(request.role());
        }
        return UserResponse.from(user);
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
    }

    /**
     * Admins see the whole organization (returns {@code null} = no restriction).
     * Managers see themselves plus every user in a team or project they manage.
     */
    private Set<UUID> visibleUserIdsOrNull(AuthenticatedUser principal) {
        if (principal.role() == UserRole.ORGANIZATION_ADMIN) {
            return null;
        }
        Set<UUID> ids = new HashSet<>();
        ids.add(principal.id());
        ids.addAll(teamMemberRepository.findUserIdsInTeamsWhereUserHasRole(principal.id(), MembershipRole.MANAGER));
        ids.addAll(projectMemberRepository.findUserIdsInProjectsWhereUserHasRole(principal.id(), MembershipRole.MANAGER));
        return ids;
    }

    private Specification<AppUser> userSpecification(UUID organizationId, String search, UserRole role,
                                                     Boolean active, Set<UUID> visibleIds) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("organization").get("id"), organizationId));
            if (role != null) {
                predicates.add(cb.equal(root.get("role"), role));
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

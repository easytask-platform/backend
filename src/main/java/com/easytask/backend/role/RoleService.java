package com.easytask.backend.role;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ConflictException;
import com.easytask.backend.common.ItemsResponse;
import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.common.ValidationException;
import com.easytask.backend.user.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;
    private final AppUserRepository userRepository;
    private final com.easytask.backend.organization.OrganizationRepository organizationRepository;

    @Transactional(readOnly = true)
    public ItemsResponse<PermissionResponse> permissionCatalog() {
        return new ItemsResponse<>(Arrays.stream(PermissionCode.values())
                .map(PermissionResponse::from)
                .toList());
    }

    @Transactional(readOnly = true)
    public ItemsResponse<RoleResponse> list(AuthenticatedUser principal) {
        List<Role> roles = roleRepository.findByOrganizationIdOrderByNameAsc(principal.organizationId());
        return new ItemsResponse<>(roles.stream()
                .map(role -> RoleResponse.from(role, userRepository.countByRoleId(role.getId())))
                .toList());
    }

    @Transactional(readOnly = true)
    public RoleResponse get(AuthenticatedUser principal, UUID roleId) {
        Role role = requireRole(principal, roleId);
        return RoleResponse.from(role, userRepository.countByRoleId(role.getId()));
    }

    @Transactional
    public RoleResponse create(AuthenticatedUser principal, CreateRoleRequest request) {
        if (roleRepository.existsByOrganizationIdAndNameIgnoreCase(
                principal.organizationId(), request.name())) {
            throw new ConflictException("A role with this name already exists");
        }
        Role role = roleRepository.save(Role.builder()
                .organization(organizationRepository.getReferenceById(principal.organizationId()))
                .name(request.name())
                .dataScope(request.dataScope())
                .system(false)
                .permissions(validatePermissions(request.permissions()))
                .build());
        return RoleResponse.from(role, 0);
    }

    @Transactional
    public RoleResponse update(AuthenticatedUser principal, UUID roleId, UpdateRoleRequest request) {
        Role role = requireRole(principal, roleId);
        if (role.isSystem()) {
            throw new ConflictException("System roles cannot be modified");
        }
        if (request.name() != null && !request.name().equalsIgnoreCase(role.getName())
                && roleRepository.existsByOrganizationIdAndNameIgnoreCase(
                        principal.organizationId(), request.name())) {
            throw new ConflictException("A role with this name already exists");
        }
        if (request.name() != null) {
            role.setName(request.name());
        }
        if (request.dataScope() != null) {
            role.setDataScope(request.dataScope());
        }
        if (request.permissions() != null) {
            if (request.permissions().isEmpty()) {
                throw new ValidationException("A role needs at least one permission", Map.of());
            }
            role.setPermissions(validatePermissions(request.permissions()));
        }
        // Users holding this role keep stale claims until their access token
        // expires; refresh reissues claims from the updated role.
        return RoleResponse.from(role, userRepository.countByRoleId(role.getId()));
    }

    @Transactional
    public void delete(AuthenticatedUser principal, UUID roleId) {
        Role role = requireRole(principal, roleId);
        if (role.isSystem()) {
            throw new ConflictException("System roles cannot be deleted");
        }
        long holders = userRepository.countByRoleId(role.getId());
        if (holders > 0) {
            throw new ConflictException(
                    "Role is assigned to %d user(s); reassign them first".formatted(holders));
        }
        roleRepository.delete(role);
    }

    private Role requireRole(AuthenticatedUser principal, UUID roleId) {
        return roleRepository.findByIdAndOrganizationId(roleId, principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Role not found"));
    }

    /** Every code must exist in the catalog; unknown codes are a field error. */
    private Set<String> validatePermissions(Set<String> requested) {
        Map<String, String> unknown = new HashMap<>();
        for (String code : requested) {
            if (PermissionCode.fromCode(code).isEmpty()) {
                unknown.put(code, "Unknown permission code");
            }
        }
        if (!unknown.isEmpty()) {
            throw new ValidationException("Unknown permission codes", unknown);
        }
        return Set.copyOf(requested);
    }
}

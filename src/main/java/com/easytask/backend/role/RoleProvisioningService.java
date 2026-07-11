package com.easytask.backend.role;

import com.easytask.backend.organization.Organization;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Creates the three locked system roles for a new organization (decision D9).
 * Existing organizations were provisioned by the V3 migration; this service
 * covers organizations registered after it. Callers must be transactional.
 */
@Service
public class RoleProvisioningService {

    public static final String ADMIN = "ORGANIZATION_ADMIN";
    public static final String MANAGER = "MANAGER";
    public static final String EMPLOYEE = "EMPLOYEE";

    private static final Set<PermissionCode> MANAGER_PERMISSIONS = Set.of(
            PermissionCode.USER_READ,
            PermissionCode.TEAM_READ,
            PermissionCode.PROJECT_MANAGE,
            PermissionCode.TASK_MANAGE,
            PermissionCode.TASK_REVIEW,
            PermissionCode.TASK_CANCEL,
            PermissionCode.RECURRING_MANAGE,
            PermissionCode.DASHBOARD_MANAGER);

    private final RoleRepository roleRepository;

    public RoleProvisioningService(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    /** Provisions the system roles and returns the admin role for the founder. */
    public Role provisionSystemRoles(Organization organization) {
        Role admin = save(organization, ADMIN, DataScope.ORGANIZATION,
                Set.of(PermissionCode.values()));
        save(organization, MANAGER, DataScope.MANAGED, MANAGER_PERMISSIONS);
        save(organization, EMPLOYEE, DataScope.ASSIGNED, Set.of(PermissionCode.TASK_EXECUTE));
        return admin;
    }

    private Role save(Organization organization, String name, DataScope scope,
                      Set<PermissionCode> permissions) {
        return roleRepository.save(Role.builder()
                .organization(organization)
                .name(name)
                .dataScope(scope)
                .system(true)
                .permissions(permissions.stream()
                        .map(PermissionCode::code)
                        .collect(Collectors.toSet()))
                .build());
    }
}

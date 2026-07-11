package com.easytask.backend.role;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ItemsResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('role:manage')")
    public ItemsResponse<PermissionResponse> permissions() {
        return roleService.permissionCatalog();
    }

    /** Listing needs only user:read so user-facing pickers can show role names. */
    @GetMapping("/roles")
    @PreAuthorize("hasAnyAuthority('user:read', 'role:manage')")
    public ItemsResponse<RoleResponse> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return roleService.list(principal);
    }

    @GetMapping("/roles/{roleId}")
    @PreAuthorize("hasAnyAuthority('user:read', 'role:manage')")
    public RoleResponse get(@AuthenticationPrincipal AuthenticatedUser principal,
                            @PathVariable UUID roleId) {
        return roleService.get(principal, roleId);
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('role:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleResponse create(@AuthenticationPrincipal AuthenticatedUser principal,
                               @Valid @RequestBody CreateRoleRequest request) {
        return roleService.create(principal, request);
    }

    @PatchMapping("/roles/{roleId}")
    @PreAuthorize("hasAuthority('role:manage')")
    public RoleResponse update(@AuthenticationPrincipal AuthenticatedUser principal,
                               @PathVariable UUID roleId,
                               @Valid @RequestBody UpdateRoleRequest request) {
        return roleService.update(principal, roleId, request);
    }

    @DeleteMapping("/roles/{roleId}")
    @PreAuthorize("hasAuthority('role:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser principal,
                       @PathVariable UUID roleId) {
        roleService.delete(principal, roleId);
    }
}

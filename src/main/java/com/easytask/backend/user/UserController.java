package com.easytask.backend.user;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    @PreAuthorize("hasAuthority('user:read')")
    public PageResponse<UserResponse> list(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @RequestParam(required = false) String search,
                                           @RequestParam(required = false) String role,
                                           @RequestParam(required = false) Boolean active,
                                           @PageableDefault(sort = "createdAt") Pageable pageable) {
        return userService.list(principal, search, role, active, pageable);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('user:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@AuthenticationPrincipal AuthenticatedUser principal,
                               @Valid @RequestBody CreateUserRequest request) {
        return userService.create(principal, request);
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAuthority('user:read')")
    public UserResponse get(@AuthenticationPrincipal AuthenticatedUser principal,
                            @PathVariable UUID userId) {
        return userService.get(principal, userId);
    }

    @PatchMapping("/{userId}")
    @PreAuthorize("hasAuthority('user:manage')")
    public UserResponse update(@AuthenticationPrincipal AuthenticatedUser principal,
                               @PathVariable UUID userId,
                               @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(principal, userId, request);
    }

    @PatchMapping("/{userId}/password")
    @PreAuthorize("hasAuthority('user:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@AuthenticationPrincipal AuthenticatedUser principal,
                              @PathVariable UUID userId,
                              @Valid @RequestBody AdminResetPasswordRequest request) {
        userService.resetPassword(principal, userId, request);
    }

    @PatchMapping("/{userId}/deactivate")
    @PreAuthorize("hasAuthority('user:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@AuthenticationPrincipal AuthenticatedUser principal,
                           @PathVariable UUID userId) {
        userService.deactivate(principal, userId);
    }
}

package com.easytask.backend.project;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ItemsResponse;
import com.easytask.backend.common.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping
    public PageResponse<ProjectResponse> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) ProjectStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueTo,
            @PageableDefault(sort = "name") Pageable pageable) {
        return projectService.list(principal, search, status, dueFrom, dueTo, pageable);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN', 'MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@AuthenticationPrincipal AuthenticatedUser principal,
                                  @Valid @RequestBody CreateProjectRequest request) {
        return projectService.create(principal, request);
    }

    @GetMapping("/{projectId}")
    public ProjectDetailResponse get(@AuthenticationPrincipal AuthenticatedUser principal,
                                     @PathVariable UUID projectId) {
        return projectService.get(principal, projectId);
    }

    @PatchMapping("/{projectId}")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN', 'MANAGER')")
    public ProjectResponse update(@AuthenticationPrincipal AuthenticatedUser principal,
                                  @PathVariable UUID projectId,
                                  @Valid @RequestBody UpdateProjectRequest request) {
        return projectService.update(principal, projectId, request);
    }

    @GetMapping("/{projectId}/members")
    public ItemsResponse<ProjectMemberResponse> members(@AuthenticationPrincipal AuthenticatedUser principal,
                                                        @PathVariable UUID projectId) {
        return projectService.members(principal, projectId);
    }

    @PostMapping("/{projectId}/members")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN', 'MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectMemberResponse addMember(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @PathVariable UUID projectId,
                                           @Valid @RequestBody AddProjectMemberRequest request) {
        return projectService.addMember(principal, projectId, request);
    }

    @DeleteMapping("/{projectId}/members/{userId}")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN', 'MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal AuthenticatedUser principal,
                             @PathVariable UUID projectId,
                             @PathVariable UUID userId) {
        projectService.removeMember(principal, projectId, userId);
    }
}

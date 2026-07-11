package com.easytask.backend.team;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ItemsResponse;
import com.easytask.backend.common.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

    @GetMapping
    @PreAuthorize("hasAuthority('team:read')")
    public PageResponse<TeamResponse> list(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @RequestParam(required = false) String search,
                                           @PageableDefault(sort = "name") Pageable pageable) {
        return teamService.list(principal, search, pageable);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('team:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public TeamResponse create(@AuthenticationPrincipal AuthenticatedUser principal,
                               @Valid @RequestBody CreateTeamRequest request) {
        return teamService.create(principal, request);
    }

    @PatchMapping("/{teamId}")
    @PreAuthorize("hasAuthority('team:manage')")
    public TeamResponse update(@AuthenticationPrincipal AuthenticatedUser principal,
                               @PathVariable UUID teamId,
                               @Valid @RequestBody UpdateTeamRequest request) {
        return teamService.update(principal, teamId, request);
    }

    @GetMapping("/{teamId}/members")
    @PreAuthorize("hasAuthority('team:read')")
    public ItemsResponse<TeamMemberResponse> members(@AuthenticationPrincipal AuthenticatedUser principal,
                                                     @PathVariable UUID teamId) {
        return teamService.members(principal, teamId);
    }

    @PostMapping("/{teamId}/members")
    @PreAuthorize("hasAuthority('team:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public TeamMemberResponse addMember(@AuthenticationPrincipal AuthenticatedUser principal,
                                        @PathVariable UUID teamId,
                                        @Valid @RequestBody AddTeamMemberRequest request) {
        return teamService.addMember(principal, teamId, request);
    }

    @DeleteMapping("/{teamId}/members/{userId}")
    @PreAuthorize("hasAuthority('team:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal AuthenticatedUser principal,
                             @PathVariable UUID teamId,
                             @PathVariable UUID userId) {
        teamService.removeMember(principal, teamId, userId);
    }
}

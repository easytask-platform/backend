package com.easytask.backend.tag;

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
public class TagController {

    private final TagService tagService;

    @GetMapping("/projects/{projectId}/tags")
    public ItemsResponse<TagResponse> list(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @PathVariable UUID projectId) {
        return tagService.list(principal, projectId);
    }

    @PostMapping("/projects/{projectId}/tags")
    @PreAuthorize("hasAuthority('task:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public TagResponse create(@AuthenticationPrincipal AuthenticatedUser principal,
                              @PathVariable UUID projectId,
                              @Valid @RequestBody CreateTagRequest request) {
        return tagService.create(principal, projectId, request);
    }

    @PatchMapping("/tags/{tagId}")
    @PreAuthorize("hasAuthority('task:manage')")
    public TagResponse update(@AuthenticationPrincipal AuthenticatedUser principal,
                              @PathVariable UUID tagId,
                              @Valid @RequestBody UpdateTagRequest request) {
        return tagService.update(principal, tagId, request);
    }

    @DeleteMapping("/tags/{tagId}")
    @PreAuthorize("hasAuthority('task:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser principal,
                       @PathVariable UUID tagId) {
        tagService.delete(principal, tagId);
    }
}

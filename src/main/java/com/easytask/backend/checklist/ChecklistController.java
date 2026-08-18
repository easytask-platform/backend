package com.easytask.backend.checklist;

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
public class ChecklistController {

    private final ChecklistService checklistService;

    @GetMapping("/tasks/{taskId}/checklist")
    public ItemsResponse<ChecklistItemResponse> list(@AuthenticationPrincipal AuthenticatedUser principal,
                                                     @PathVariable UUID taskId) {
        return checklistService.list(principal, taskId);
    }

    @PostMapping("/tasks/{taskId}/checklist")
    @PreAuthorize("hasAuthority('task:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public ChecklistItemResponse create(@AuthenticationPrincipal AuthenticatedUser principal,
                                        @PathVariable UUID taskId,
                                        @Valid @RequestBody CreateChecklistItemRequest request) {
        return checklistService.create(principal, taskId, request);
    }

    /** No {@code @PreAuthorize}: assignees without task:manage may toggle {@code done}. */
    @PatchMapping("/checklist-items/{itemId}")
    public ChecklistItemResponse update(@AuthenticationPrincipal AuthenticatedUser principal,
                                        @PathVariable UUID itemId,
                                        @Valid @RequestBody UpdateChecklistItemRequest request) {
        return checklistService.update(principal, itemId, request);
    }

    @DeleteMapping("/checklist-items/{itemId}")
    @PreAuthorize("hasAuthority('task:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser principal,
                       @PathVariable UUID itemId) {
        checklistService.delete(principal, itemId);
    }
}

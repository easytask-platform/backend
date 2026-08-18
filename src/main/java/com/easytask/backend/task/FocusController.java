package com.easytask.backend.task;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ItemsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The caller's daily-focus pins (P4-7, D33). */
@RestController
@RequestMapping("/api/v1/me/focus")
@RequiredArgsConstructor
public class FocusController {

    private final TaskPinService taskPinService;

    @GetMapping
    public ItemsResponse<TaskListItemResponse> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return taskPinService.list(principal);
    }

    @PostMapping("/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pin(@AuthenticationPrincipal AuthenticatedUser principal,
                    @PathVariable UUID taskId) {
        taskPinService.pin(principal, taskId);
    }

    @DeleteMapping("/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unpin(@AuthenticationPrincipal AuthenticatedUser principal,
                      @PathVariable UUID taskId) {
        taskPinService.unpin(principal, taskId);
    }
}

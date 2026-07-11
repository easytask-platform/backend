package com.easytask.backend.task;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @GetMapping
    public PageResponse<TaskListItemResponse> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) TaskPriority priority,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID assigneeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueTo,
            @RequestParam(required = false) Boolean overdue,
            @PageableDefault(sort = "createdAt") Pageable pageable) {
        return taskService.list(principal,
                new TaskService.TaskFilters(search, status, priority, projectId, assigneeId, dueFrom, dueTo,
                        overdue),
                pageable);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('task:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDetailResponse create(@AuthenticationPrincipal AuthenticatedUser principal,
                                     @Valid @RequestBody CreateTaskRequest request) {
        return taskService.create(principal, request);
    }

    @GetMapping("/{taskId}")
    public TaskDetailResponse get(@AuthenticationPrincipal AuthenticatedUser principal,
                                  @PathVariable UUID taskId) {
        return taskService.get(principal, taskId);
    }

    @PatchMapping("/{taskId}")
    @PreAuthorize("hasAuthority('task:manage')")
    public TaskDetailResponse update(@AuthenticationPrincipal AuthenticatedUser principal,
                                     @PathVariable UUID taskId,
                                     @Valid @RequestBody UpdateTaskRequest request) {
        return taskService.update(principal, taskId, request);
    }

    @PatchMapping("/{taskId}/status")
    public TaskDetailResponse changeStatus(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @PathVariable UUID taskId,
                                           @Valid @RequestBody UpdateTaskStatusRequest request) {
        return taskService.changeStatus(principal, taskId, request);
    }
}

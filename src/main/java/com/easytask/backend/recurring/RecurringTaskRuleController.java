package com.easytask.backend.recurring;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ItemsResponse;
import com.easytask.backend.common.PageResponse;
import com.easytask.backend.task.TaskListItemResponse;
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
@RequestMapping("/api/v1/recurring-task-rules")
@RequiredArgsConstructor
public class RecurringTaskRuleController {

    private final RecurringTaskRuleService ruleService;

    @GetMapping
    @PreAuthorize("hasAuthority('recurring:manage')")
    public PageResponse<RecurringTaskRuleResponse> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) RecurrenceFrequency frequency,
            @PageableDefault(sort = "createdAt") Pageable pageable) {
        return ruleService.list(principal, projectId, frequency, pageable);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('recurring:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public RecurringTaskRuleResponse create(@AuthenticationPrincipal AuthenticatedUser principal,
                                            @Valid @RequestBody CreateRecurringTaskRuleRequest request) {
        return ruleService.create(principal, request);
    }

    @GetMapping("/{ruleId}/tasks")
    @PreAuthorize("hasAuthority('recurring:manage')")
    public PageResponse<TaskListItemResponse> generatedTasks(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID ruleId,
            @PageableDefault(sort = "createdAt") Pageable pageable) {
        return ruleService.generatedTasks(principal, ruleId, pageable);
    }

    // --- Occurrences + exceptions (P4-10, D41) --------------------------------

    @GetMapping("/{ruleId}/occurrences")
    @PreAuthorize("hasAuthority('recurring:manage')")
    public ItemsResponse<OccurrenceResponse> occurrences(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID ruleId,
            @RequestParam(defaultValue = "6") int count) {
        return ruleService.occurrences(principal, ruleId, count);
    }

    @PostMapping("/{ruleId}/exceptions")
    @PreAuthorize("hasAuthority('recurring:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addException(@AuthenticationPrincipal AuthenticatedUser principal,
                             @PathVariable UUID ruleId,
                             @Valid @RequestBody CreateExceptionRequest request) {
        ruleService.addException(principal, ruleId, request.date());
    }

    @DeleteMapping("/{ruleId}/exceptions/{date}")
    @PreAuthorize("hasAuthority('recurring:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeException(@AuthenticationPrincipal AuthenticatedUser principal,
                                @PathVariable UUID ruleId,
                                @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        ruleService.removeException(principal, ruleId, date);
    }
}

package com.easytask.backend.timeentry;

import com.easytask.backend.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
public class TimeEntryController {

    private final TimeEntryService timeEntryService;

    @GetMapping("/tasks/{taskId}/time-entries")
    public TimeEntryListResponse list(@AuthenticationPrincipal AuthenticatedUser principal,
                                      @PathVariable UUID taskId) {
        return timeEntryService.list(principal, taskId);
    }

    @PostMapping("/tasks/{taskId}/time-entries")
    @ResponseStatus(HttpStatus.CREATED)
    public TimeEntryResponse create(@AuthenticationPrincipal AuthenticatedUser principal,
                                    @PathVariable UUID taskId,
                                    @Valid @RequestBody TimeEntryRequest request) {
        return timeEntryService.create(principal, taskId, request);
    }

    @PatchMapping("/time-entries/{timeEntryId}")
    public TimeEntryResponse update(@AuthenticationPrincipal AuthenticatedUser principal,
                                    @PathVariable UUID timeEntryId,
                                    @Valid @RequestBody UpdateTimeEntryRequest request) {
        return timeEntryService.update(principal, timeEntryId, request);
    }

    @DeleteMapping("/time-entries/{timeEntryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser principal,
                       @PathVariable UUID timeEntryId) {
        timeEntryService.delete(principal, timeEntryId);
    }
}

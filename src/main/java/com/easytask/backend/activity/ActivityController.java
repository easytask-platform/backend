package com.easytask.backend.activity;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ItemsResponse;
import com.easytask.backend.task.TaskAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ActivityController {

    private final TaskActivityLogRepository activityLogRepository;
    private final TaskAccessService taskAccessService;

    @GetMapping("/tasks/{taskId}/activity")
    @Transactional(readOnly = true)
    public ItemsResponse<ActivityLogResponse> list(@AuthenticationPrincipal AuthenticatedUser principal,
                                                   @PathVariable UUID taskId) {
        taskAccessService.getVisibleTask(principal, taskId);
        return new ItemsResponse<>(activityLogRepository.findAllByTaskIdOrderByCreatedAtDesc(taskId).stream()
                .map(ActivityLogResponse::from)
                .toList());
    }
}

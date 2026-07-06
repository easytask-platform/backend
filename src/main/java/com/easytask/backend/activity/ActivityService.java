package com.easytask.backend.activity;

import com.easytask.backend.task.Task;
import com.easytask.backend.user.AppUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Writes activity log entries synchronously inside the caller's transaction
 * (confirmed decision: no async infra in v1).
 */
@Service
@RequiredArgsConstructor
public class ActivityService {

    private final TaskActivityLogRepository activityLogRepository;

    public void log(Task task, AppUser actor, ActivityEventType eventType, String oldValue, String newValue) {
        activityLogRepository.save(TaskActivityLog.builder()
                .task(task)
                .actor(actor)
                .eventType(eventType)
                .oldValue(oldValue)
                .newValue(newValue)
                .build());
    }
}

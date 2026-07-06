package com.easytask.backend.timeentry;

import com.easytask.backend.activity.ActivityEventType;
import com.easytask.backend.activity.ActivityService;
import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ConflictException;
import com.easytask.backend.common.ForbiddenException;
import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.notification.NotificationService;
import com.easytask.backend.notification.NotificationType;
import com.easytask.backend.task.Task;
import com.easytask.backend.task.TaskAccessService;
import com.easytask.backend.task.TaskAssignment;
import com.easytask.backend.task.TaskAssignmentRepository;
import com.easytask.backend.task.TaskStatus;
import com.easytask.backend.user.AppUser;
import com.easytask.backend.user.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TimeEntryService {

    private final TimeEntryRepository timeEntryRepository;
    private final TaskAssignmentRepository taskAssignmentRepository;
    private final TaskAccessService taskAccessService;
    private final AppUserRepository userRepository;
    private final ActivityService activityService;
    private final NotificationService notificationService;

    @Transactional(readOnly = true)
    public TimeEntryListResponse list(AuthenticatedUser principal, UUID taskId) {
        Task task = taskAccessService.getVisibleTask(principal, taskId);
        return new TimeEntryListResponse(
                timeEntryRepository.findAllForTask(taskId).stream().map(TimeEntryResponse::from).toList(),
                timeEntryRepository.totalHoursForTask(taskId),
                task.getEstimatedHours());
    }

    @Transactional
    public TimeEntryResponse create(AuthenticatedUser principal, UUID taskId, TimeEntryRequest request) {
        Task task = taskAccessService.getVisibleTask(principal, taskId);
        TaskAssignment assignment = taskAssignmentRepository.findByTaskIdAndAssigneeId(taskId, principal.id())
                .orElseThrow(() -> new ForbiddenException("Only assignees can log time on a task"));
        requireEditableTask(task);

        TimeEntry entry = timeEntryRepository.save(TimeEntry.builder()
                .taskAssignment(assignment)
                .workDate(request.workDate())
                .hoursSpent(request.hoursSpent())
                .note(request.note())
                .build());

        AppUser actor = userRepository.getReferenceById(principal.id());
        activityService.log(task, actor, ActivityEventType.TIME_LOGGED, null,
                request.hoursSpent().toPlainString() + "h");
        notificationService.notifyAllExceptActor(taskAccessService.interestedUsers(task), principal.id(), task,
                NotificationType.TIME_LOGGED,
                "%s logged %sh on task '%s'".formatted(assignment.getAssignee().getFullName(),
                        request.hoursSpent().toPlainString(), task.getTitle()));
        return TimeEntryResponse.from(entry);
    }

    @Transactional
    public TimeEntryResponse update(AuthenticatedUser principal, UUID timeEntryId,
                                    UpdateTimeEntryRequest request) {
        TimeEntry entry = requireOwnEditableEntry(principal, timeEntryId);
        if (request.workDate() != null) {
            entry.setWorkDate(request.workDate());
        }
        if (request.hoursSpent() != null) {
            entry.setHoursSpent(request.hoursSpent());
        }
        if (request.note() != null) {
            entry.setNote(request.note());
        }
        return TimeEntryResponse.from(entry);
    }

    @Transactional
    public void delete(AuthenticatedUser principal, UUID timeEntryId) {
        timeEntryRepository.delete(requireOwnEditableEntry(principal, timeEntryId));
    }

    private TimeEntry requireOwnEditableEntry(AuthenticatedUser principal, UUID timeEntryId) {
        TimeEntry entry = timeEntryRepository.findById(timeEntryId)
                .orElseThrow(() -> new NotFoundException("Time entry not found"));
        Task task = taskAccessService.getVisibleTask(principal,
                entry.getTaskAssignment().getTask().getId());
        if (!entry.getTaskAssignment().getAssignee().getId().equals(principal.id())) {
            throw new ForbiddenException("Only the time entry owner can modify it");
        }
        requireEditableTask(task);
        return entry;
    }

    private static void requireEditableTask(Task task) {
        if (task.getStatus() == TaskStatus.APPROVED || task.getStatus() == TaskStatus.CANCELLED) {
            throw new ConflictException("Time entries are locked once the task is approved or cancelled");
        }
    }
}

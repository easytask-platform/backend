package com.easytask.backend.comment;

import com.easytask.backend.activity.ActivityEventType;
import com.easytask.backend.activity.ActivityService;
import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ForbiddenException;
import com.easytask.backend.common.ItemsResponse;
import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.notification.NotificationService;
import com.easytask.backend.notification.NotificationType;
import com.easytask.backend.task.Task;
import com.easytask.backend.task.TaskAccessService;
import com.easytask.backend.user.AppUser;
import com.easytask.backend.user.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CommentService {

    private final TaskCommentRepository commentRepository;
    private final TaskAccessService taskAccessService;
    private final AppUserRepository userRepository;
    private final ActivityService activityService;
    private final NotificationService notificationService;

    @Transactional(readOnly = true)
    public ItemsResponse<CommentResponse> list(AuthenticatedUser principal, UUID taskId) {
        taskAccessService.getVisibleTask(principal, taskId);
        return new ItemsResponse<>(commentRepository.findAllByTaskIdOrderByCreatedAtAsc(taskId).stream()
                .map(CommentResponse::from)
                .toList());
    }

    @Transactional
    public CommentResponse create(AuthenticatedUser principal, UUID taskId, CommentTextRequest request) {
        Task task = taskAccessService.getVisibleTask(principal, taskId);
        AppUser author = userRepository.getReferenceById(principal.id());
        TaskComment comment = commentRepository.save(TaskComment.builder()
                .task(task)
                .author(author)
                .content(request.text())
                .build());
        activityService.log(task, author, ActivityEventType.COMMENT_POSTED, null, request.text());
        notificationService.notifyAllExceptActor(taskAccessService.interestedUsers(task), principal.id(), task,
                NotificationType.COMMENT_ADDED, "New comment on task '%s'".formatted(task.getTitle()));
        return CommentResponse.from(comment);
    }

    @Transactional
    public CommentResponse update(AuthenticatedUser principal, UUID commentId, CommentTextRequest request) {
        TaskComment comment = requireAuthorsComment(principal, commentId);
        comment.setContent(request.text());
        return CommentResponse.from(comment);
    }

    @Transactional
    public void delete(AuthenticatedUser principal, UUID commentId) {
        commentRepository.delete(requireAuthorsComment(principal, commentId));
    }

    private TaskComment requireAuthorsComment(AuthenticatedUser principal, UUID commentId) {
        TaskComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new NotFoundException("Comment not found"));
        // out-of-scope task (other org / not visible) must read as 404, not as a permission hint
        taskAccessService.getVisibleTask(principal, comment.getTask().getId());
        if (!comment.getAuthor().getId().equals(principal.id())) {
            throw new ForbiddenException("Only the comment author can modify it");
        }
        return comment;
    }
}

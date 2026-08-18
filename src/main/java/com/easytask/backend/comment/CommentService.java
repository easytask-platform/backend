package com.easytask.backend.comment;

import com.easytask.backend.activity.ActivityEventType;
import com.easytask.backend.activity.ActivityService;
import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ForbiddenException;
import com.easytask.backend.common.ItemsResponse;
import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.common.ValidationException;
import com.easytask.backend.notification.NotificationService;
import com.easytask.backend.notification.NotificationType;
import com.easytask.backend.task.Task;
import com.easytask.backend.task.TaskAccessService;
import com.easytask.backend.user.AppUser;
import com.easytask.backend.user.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    public CommentResponse create(AuthenticatedUser principal, UUID taskId, CreateCommentRequest request) {
        Task task = taskAccessService.getVisibleTask(principal, taskId);
        TaskComment parent = resolveParent(taskId, request.parentCommentId());
        List<AppUser> mentioned = resolveMentions(principal, task, request.mentionedUserIds());
        AppUser author = userRepository.getReferenceById(principal.id());
        TaskComment comment = commentRepository.save(TaskComment.builder()
                .task(task)
                .author(author)
                .parent(parent)
                .content(request.text())
                .build());
        activityService.log(task, author, ActivityEventType.COMMENT_POSTED, null, request.text());
        notify(principal, task, author, parent, mentioned);
        return CommentResponse.from(comment);
    }

    /** The parent must be a top-level comment on the same task (one-level threading, D34). */
    private TaskComment resolveParent(UUID taskId, UUID parentCommentId) {
        if (parentCommentId == null) {
            return null;
        }
        TaskComment parent = commentRepository.findById(parentCommentId)
                .filter(candidate -> candidate.getTask().getId().equals(taskId))
                .orElseThrow(() -> new ValidationException("parentCommentId",
                        "Parent comment must belong to the same task"));
        if (parent.getParent() != null) {
            throw new ValidationException("parentCommentId", "Cannot reply to a reply");
        }
        return parent;
    }

    /**
     * Mentioned users must be able to open the task (same-org + the getVisibleTask
     * rule), so mentions cannot leak task existence (D35). Self-mentions are
     * silently dropped — the actor never notifies themselves.
     */
    private List<AppUser> resolveMentions(AuthenticatedUser principal, Task task, List<UUID> mentionedUserIds) {
        if (mentionedUserIds == null || mentionedUserIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = new LinkedHashSet<>(mentionedUserIds);
        ids.remove(principal.id());
        if (ids.isEmpty()) {
            return List.of();
        }
        List<AppUser> users = userRepository.findAllByIdInAndOrganizationId(ids, principal.organizationId());
        if (users.size() != ids.size()
                || !users.stream().allMatch(user -> taskAccessService.canSee(user, task))) {
            throw new ValidationException("mentionedUserIds",
                    "Every mentioned user must be able to access the task");
        }
        return users;
    }

    /**
     * COMMENT_ADDED fans out to the interested users minus the mentioned users and
     * (for replies) the parent author, who instead get the more specific
     * COMMENT_MENTION / COMMENT_REPLIED — never two notifications for one comment.
     */
    private void notify(AuthenticatedUser principal, Task task, AppUser author,
                        TaskComment parent, List<AppUser> mentioned) {
        Set<UUID> excluded = new HashSet<>();
        mentioned.forEach(user -> excluded.add(user.getId()));
        if (parent != null) {
            excluded.add(parent.getAuthor().getId());
        }
        List<AppUser> generalRecipients = taskAccessService.interestedUsers(task).stream()
                .filter(user -> !excluded.contains(user.getId()))
                .toList();
        notificationService.notifyAllExceptActor(generalRecipients, principal.id(), task,
                NotificationType.COMMENT_ADDED, "New comment on task '%s'".formatted(task.getTitle()));
        if (parent != null) {
            notificationService.notifyAllExceptActor(List.of(parent.getAuthor()), principal.id(), task,
                    NotificationType.COMMENT_REPLIED,
                    "%s replied to your comment on task '%s'".formatted(author.getFullName(), task.getTitle()));
        }
        if (!mentioned.isEmpty()) {
            notificationService.notifyAllExceptActor(mentioned, principal.id(), task,
                    NotificationType.COMMENT_MENTION,
                    "You were mentioned in a comment on task '%s'".formatted(task.getTitle()));
        }
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

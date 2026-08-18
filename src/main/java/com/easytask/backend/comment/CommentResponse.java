package com.easytask.backend.comment;

import com.easytask.backend.user.AvatarUrls;

import java.time.Instant;
import java.util.UUID;

public record CommentResponse(
        UUID id,
        UUID taskId,
        UUID parentCommentId,
        Author author,
        String text,
        Instant createdAt,
        Instant updatedAt
) {

    public record Author(UUID id, String fullName, String avatarUrl) {
    }

    public static CommentResponse from(TaskComment comment) {
        return new CommentResponse(comment.getId(), comment.getTask().getId(),
                comment.getParent() == null ? null : comment.getParent().getId(),
                new Author(comment.getAuthor().getId(), comment.getAuthor().getFullName(),
                        AvatarUrls.of(comment.getAuthor())),
                comment.getContent(), comment.getCreatedAt(), comment.getUpdatedAt());
    }
}

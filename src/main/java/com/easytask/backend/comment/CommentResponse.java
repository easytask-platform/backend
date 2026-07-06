package com.easytask.backend.comment;

import java.time.Instant;
import java.util.UUID;

public record CommentResponse(
        UUID id,
        UUID taskId,
        Author author,
        String text,
        Instant createdAt,
        Instant updatedAt
) {

    public record Author(UUID id, String fullName) {
    }

    public static CommentResponse from(TaskComment comment) {
        return new CommentResponse(comment.getId(), comment.getTask().getId(),
                new Author(comment.getAuthor().getId(), comment.getAuthor().getFullName()),
                comment.getContent(), comment.getCreatedAt(), comment.getUpdatedAt());
    }
}

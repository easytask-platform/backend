package com.easytask.backend.comment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Body for POST /tasks/{taskId}/comments. {@code parentCommentId} makes the
 * comment a reply (one level deep, D34); {@code mentionedUserIds} lists
 * explicitly @-mentioned users (D35) — the server never parses the text.
 */
public record CreateCommentRequest(
        @NotBlank @Size(max = 5000) String text,
        UUID parentCommentId,
        List<UUID> mentionedUserIds
) {
}

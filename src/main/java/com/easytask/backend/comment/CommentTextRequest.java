package com.easytask.backend.comment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body for the PATCH comment endpoint (POST uses {@link CreateCommentRequest}). */
public record CommentTextRequest(
        @NotBlank @Size(max = 5000) String text
) {
}

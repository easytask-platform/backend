package com.easytask.backend.comment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body for both POST and PATCH comment endpoints. */
public record CommentTextRequest(
        @NotBlank @Size(max = 5000) String text
) {
}

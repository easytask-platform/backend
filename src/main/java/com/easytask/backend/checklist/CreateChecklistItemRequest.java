package com.easytask.backend.checklist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateChecklistItemRequest(
        @NotBlank @Size(max = 150) String title
) {
}

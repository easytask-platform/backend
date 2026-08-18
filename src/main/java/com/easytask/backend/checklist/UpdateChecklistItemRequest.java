package com.easytask.backend.checklist;

import jakarta.validation.constraints.Size;

/**
 * All fields optional; only non-null values are applied. {@code done} may be
 * toggled by task assignees or {@code task:manage}; {@code title} and
 * {@code position} require {@code task:manage}.
 */
public record UpdateChecklistItemRequest(
        @Size(min = 1, max = 150) String title,
        Boolean done,
        Integer position
) {
}

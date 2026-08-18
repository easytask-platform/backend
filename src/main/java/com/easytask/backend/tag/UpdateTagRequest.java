package com.easytask.backend.tag;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** All fields optional; only non-null values are applied. */
public record UpdateTagRequest(
        @Size(min = 1, max = 30) String name,
        @Pattern(regexp = "^#[0-9a-fA-F]{6}$",
                message = "must be a 6-digit hex color like #0ea5e9") String color
) {
}

package com.easytask.backend.tag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateTagRequest(
        @NotBlank @Size(max = 30) String name,
        @NotNull @Pattern(regexp = "^#[0-9a-fA-F]{6}$",
                message = "must be a 6-digit hex color like #0ea5e9") String color
) {
}

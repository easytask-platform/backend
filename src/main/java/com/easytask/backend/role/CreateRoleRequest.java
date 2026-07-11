package com.easytask.backend.role;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record CreateRoleRequest(
        @NotBlank @Size(min = 2, max = 50) String name,
        @NotNull DataScope dataScope,
        @NotEmpty Set<String> permissions
) {
}

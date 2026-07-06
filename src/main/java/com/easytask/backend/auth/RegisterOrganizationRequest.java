package com.easytask.backend.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterOrganizationRequest(
        @NotBlank @Size(min = 2, max = 100) String organizationName,
        @NotBlank @Size(min = 2, max = 100) String adminFullName,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 72) String password
) {
}

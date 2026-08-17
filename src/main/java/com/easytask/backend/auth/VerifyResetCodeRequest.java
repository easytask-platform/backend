package com.easytask.backend.auth;

import jakarta.validation.constraints.NotBlank;

public record VerifyResetCodeRequest(@NotBlank String token) {
}

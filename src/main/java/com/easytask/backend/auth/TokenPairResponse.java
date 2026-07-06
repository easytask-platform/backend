package com.easytask.backend.auth;

public record TokenPairResponse(
        String accessToken,
        String refreshToken
) {
}

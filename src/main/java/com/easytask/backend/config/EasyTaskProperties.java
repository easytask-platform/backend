package com.easytask.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "easytask")
public record EasyTaskProperties(
        Jwt jwt,
        Duration refreshTokenTtl,
        Storage storage
) {

    public record Jwt(String secret, Duration accessTokenTtl) {
    }

    public record Storage(String dir) {
    }
}

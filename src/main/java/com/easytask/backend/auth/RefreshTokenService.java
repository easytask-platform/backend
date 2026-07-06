package com.easytask.backend.auth;

import com.easytask.backend.common.UnauthenticatedException;
import com.easytask.backend.config.EasyTaskProperties;
import com.easytask.backend.user.AppUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Refresh tokens are opaque random strings handed to the client; only their
 * SHA-256 hash is stored. Rotation revokes the presented token and issues a
 * fresh one. Callers must be {@code @Transactional} (revocation relies on
 * dirty checking).
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;
    private final EasyTaskProperties properties;

    public String issue(AppUser user) {
        String rawToken = generateToken();
        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(sha256(rawToken))
                .expiresAt(Instant.now().plus(properties.refreshTokenTtl()))
                .build());
        return rawToken;
    }

    public RotatedToken rotate(String rawToken) {
        Instant now = Instant.now();
        RefreshToken current = refreshTokenRepository.findByTokenHash(sha256(rawToken))
                .filter(token -> token.isUsable(now))
                .orElseThrow(() -> new UnauthenticatedException("Invalid refresh token"));
        AppUser user = current.getUser();
        if (!user.isActive()) {
            throw new UnauthenticatedException("Account is deactivated");
        }
        current.setRevokedAt(now);
        return new RotatedToken(user, issue(user));
    }

    public void revoke(String rawToken, UUID userId) {
        refreshTokenRepository.findByTokenHash(sha256(rawToken))
                .filter(token -> token.getUser().getId().equals(userId))
                .filter(token -> token.getRevokedAt() == null)
                .ifPresent(token -> token.setRevokedAt(Instant.now()));
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record RotatedToken(AppUser user, String refreshToken) {
    }
}

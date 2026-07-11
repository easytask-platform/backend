package com.easytask.backend.auth;

import com.easytask.backend.common.UnauthenticatedException;
import com.easytask.backend.common.logging.SecurityAuditLog;
import com.easytask.backend.config.EasyTaskProperties;
import com.easytask.backend.user.AppUser;
import com.easytask.backend.user.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Forgot-password flow (B2). Tokens are opaque random strings stored
 * SHA-256-hashed, single-use, with a short TTL. The request endpoint never
 * reveals whether the email exists.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppUserRepository userRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetMailer mailer;
    private final PasswordEncoder passwordEncoder;
    private final EasyTaskProperties properties;
    private final SecurityAuditLog audit;

    @Transactional
    public void requestReset(ForgotPasswordRequest request) {
        var account = userRepository.findByEmailIgnoreCase(request.email())
                .filter(AppUser::isActive);
        audit.passwordResetRequested(request.email(), account.isPresent());
        account.ifPresent(user -> {
                    Instant now = Instant.now();
                    // A new request supersedes any outstanding token.
                    resetTokenRepository.invalidateAllForUser(user.getId(), now);
                    String rawToken = generateToken();
                    resetTokenRepository.save(PasswordResetToken.builder()
                            .user(user)
                            .tokenHash(sha256(rawToken))
                            .expiresAt(now.plus(properties.passwordResetTtl()))
                            .build());
                    mailer.sendResetToken(user, rawToken);
                });
        // Unknown or deactivated email: same 204 as success (no enumeration).
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        Instant now = Instant.now();
        PasswordResetToken token = resetTokenRepository.findByTokenHash(sha256(request.token()))
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> new UnauthenticatedException("Invalid or expired reset code"));
        AppUser user = token.getUser();
        if (!user.isActive()) {
            throw new UnauthenticatedException("Account is deactivated");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        token.setUsedAt(now);
        // Every existing session dies with the old password.
        refreshTokenRepository.revokeAllForUser(user.getId(), now);
        audit.passwordResetCompleted(user.getId());
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
}

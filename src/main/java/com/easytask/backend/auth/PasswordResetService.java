package com.easytask.backend.auth;

import com.easytask.backend.common.NotFoundException;
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
        // Product decision (P3): tell the user plainly when the email is
        // unknown instead of the anti-enumeration 204.
        AppUser user = account.orElseThrow(
                () -> new NotFoundException("No account found for this email"));
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
    }

    /** Code-page validation: usable token → 204; anything else → 401. */
    @Transactional(readOnly = true)
    public void verifyCode(String rawToken) {
        Instant now = Instant.now();
        resetTokenRepository.findByTokenHash(sha256(rawToken))
                .filter(t -> t.isUsable(now) && t.getUser().isActive())
                .orElseThrow(() -> new UnauthenticatedException("Invalid or expired code"));
    }

    /**
     * P3-2 (D24): invitation for a user created without a password. Reuses the
     * reset-token mechanism with a longer TTL; the invitee redeems the code
     * through the normal reset-password endpoint, which also serves as the
     * accept-invite step.
     */
    @Transactional
    public void createInvitation(AppUser user, String organizationName) {
        Instant now = Instant.now();
        resetTokenRepository.invalidateAllForUser(user.getId(), now);
        String rawToken = generateToken();
        resetTokenRepository.save(PasswordResetToken.builder()
                .user(user)
                .tokenHash(sha256(rawToken))
                .expiresAt(now.plus(properties.inviteTtl()))
                .build());
        mailer.sendInvitation(user, organizationName, rawToken);
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
        // The user picked this password themselves (reset or invite acceptance).
        user.setMustChangePassword(false);
        token.setUsedAt(now);
        // Every existing session dies with the old password.
        refreshTokenRepository.revokeAllForUser(user.getId(), now);
        audit.passwordResetCompleted(user.getId());
        mailer.sendPasswordChangedNotice(user);
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

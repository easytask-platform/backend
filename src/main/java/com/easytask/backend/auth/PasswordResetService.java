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
import java.util.HexFormat;

/**
 * Forgot-password flow (B2, reshaped in P3). Codes are 6-digit numbers
 * stored SHA-256-hashed, single-use, short TTL, rate-limited; unknown
 * emails get a plain 404 (product decision over anti-enumeration).
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
        String rawToken = generateCode();
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

    /**
     * 6-digit numeric code (P3 UX — typable from an email). token_hash is
     * UNIQUE across ALL rows (incl. consumed ones), so retry on the rare
     * collision. Low entropy is compensated by the short TTL, single-use
     * semantics, and rate limiting on all three code endpoints.
     */
    private String generateCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String code = String.format("%06d", RANDOM.nextInt(1_000_000));
            if (resetTokenRepository.findByTokenHash(sha256(code)).isEmpty()) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate a unique reset code");
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

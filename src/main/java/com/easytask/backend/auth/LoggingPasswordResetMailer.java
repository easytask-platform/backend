package com.easytask.backend.auth;

import com.easytask.backend.user.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev/demo fallback mailer: prints the reset code to the application log.
 * Used automatically when no SMTP credentials are configured — see
 * {@link com.easytask.backend.config.MailerConfiguration}.
 */
public class LoggingPasswordResetMailer implements PasswordResetMailer {

    private static final Logger log = LoggerFactory.getLogger(LoggingPasswordResetMailer.class);

    @Override
    public void sendResetToken(AppUser user, String rawToken) {
        log.info("Password reset requested for {} — reset code: {}", user.getEmail(), rawToken);
    }

    @Override
    public void sendInvitation(AppUser user, String organizationName, String rawToken) {
        log.info("Invitation for {} to join {} — invite code: {}", user.getEmail(), organizationName, rawToken);
    }
}

package com.easytask.backend.auth;

import com.easytask.backend.user.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Dev/demo mailer: prints the reset code to the application log instead of
 * sending an email (this machine has no SMTP; container-based mail catchers
 * are unreachable behind the VPN firewall).
 */
@Component
public class LoggingPasswordResetMailer implements PasswordResetMailer {

    private static final Logger log = LoggerFactory.getLogger(LoggingPasswordResetMailer.class);

    @Override
    public void sendResetToken(AppUser user, String rawToken) {
        log.info("Password reset requested for {} — reset code: {}", user.getEmail(), rawToken);
    }
}

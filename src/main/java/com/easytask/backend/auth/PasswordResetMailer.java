package com.easytask.backend.auth;

import com.easytask.backend.user.AppUser;

/**
 * Delivers the reset code to the user. The default implementation logs it
 * (no SMTP in dev); a real mail sender can replace it without touching the
 * reset flow.
 */
public interface PasswordResetMailer {

    void sendResetToken(AppUser user, String rawToken);
}

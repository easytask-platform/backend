package com.easytask.backend.auth;

import com.easytask.backend.user.AppUser;

/**
 * Delivers account emails to users. The logging implementation prints codes
 * to the app log (zero-config dev); the SMTP implementation sends real mail
 * when credentials are configured. See MailerConfiguration.
 */
public interface PasswordResetMailer {

    void sendResetToken(AppUser user, String rawToken);

    /** P3-2 (D24): "you've been added — set your password" invitation. */
    void sendInvitation(AppUser user, String organizationName, String rawToken);
}

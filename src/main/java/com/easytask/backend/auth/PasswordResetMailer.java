package com.easytask.backend.auth;

import com.easytask.backend.user.AppUser;

/**
 * Delivers account emails to users. The logging implementation prints codes
 * to the app log (zero-config dev); the SMTP implementation sends real mail
 * when credentials are configured. See MailerConfiguration.
 */
public interface PasswordResetMailer {

    void sendResetToken(AppUser user, String rawToken);

    /**
     * P3-2 (D24): "you've been added" invitation. Carries the temporary
     * password the invitee signs in with; they're forced to choose their own
     * immediately after (mustChangePassword).
     */
    void sendInvitation(AppUser user, String organizationName, String temporaryPassword);

    /**
     * Security notice after any password change — contains no secrets, exists
     * so a victim learns fast if someone else changed their password.
     * Default no-op: only real mailers need to care.
     */
    default void sendPasswordChangedNotice(AppUser user) {
    }

    /** Security notice when an administrator resets the user's password. */
    default void sendPasswordResetByAdminNotice(AppUser user) {
    }
}

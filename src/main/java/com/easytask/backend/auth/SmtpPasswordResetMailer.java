package com.easytask.backend.auth;

import com.easytask.backend.user.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Real mailer (D23): sends the reset code over SMTP. Active only when
 * SPRING_MAIL_USERNAME / SPRING_MAIL_PASSWORD are configured — see
 * {@link com.easytask.backend.config.MailerConfiguration}.
 */
public class SmtpPasswordResetMailer implements PasswordResetMailer {

    private static final Logger log = LoggerFactory.getLogger(SmtpPasswordResetMailer.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public SmtpPasswordResetMailer(JavaMailSender mailSender, String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void sendResetToken(AppUser user, String rawToken) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(user.getEmail());
        message.setSubject("EasyTask — password reset code");
        message.setText("""
                Hi %s,

                Someone requested a password reset for your EasyTask account.
                Your reset code is:

                    %s

                Enter it in the app together with your new password. The code is
                valid for 30 minutes and can be used once.

                If you didn't request this, you can safely ignore this email.

                — EasyTask
                """.formatted(user.getFullName(), rawToken));
        mailSender.send(message);
        log.info("Password reset email sent to {}", user.getEmail());
    }

    @Override
    public void sendInvitation(AppUser user, String organizationName, String temporaryPassword) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(user.getEmail());
        message.setSubject("You've been added to %s on EasyTask".formatted(organizationName));
        message.setText("""
                Hi %s,

                You've been added to %s on EasyTask. Sign in with this email
                address and the temporary password below:

                    %s

                For your security, you'll be asked to choose your own password
                right after you sign in.

                — EasyTask
                """.formatted(user.getFullName(), organizationName, temporaryPassword));
        mailSender.send(message);
        log.info("Invitation email sent to {}", user.getEmail());
    }

    @Override
    public void sendPasswordChangedNotice(AppUser user) {
        sendNoticeQuietly(user, "EasyTask — your password was changed", """
                Hi %s,

                Your EasyTask password was changed just now.

                If this was you, no action is needed. If it wasn't, contact
                your organization administrator immediately.

                — EasyTask
                """.formatted(user.getFullName()));
    }

    @Override
    public void sendPasswordResetByAdminNotice(AppUser user) {
        sendNoticeQuietly(user, "EasyTask — your password was reset", """
                Hi %s,

                An administrator reset your EasyTask password. You will receive
                the temporary password from them directly, and you'll be asked
                to choose your own the next time you log in.

                If you weren't expecting this, contact your organization
                administrator.

                — EasyTask
                """.formatted(user.getFullName()));
    }

    /** Notices are best-effort: a mail hiccup must never fail the operation. */
    private void sendNoticeQuietly(AppUser user, String subject, String text) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(user.getEmail());
            message.setSubject(subject);
            message.setText(text);
            mailSender.send(message);
            log.info("Security notice '{}' sent to {}", subject, user.getEmail());
        } catch (Exception e) {
            log.warn("Could not send security notice to {}: {}", user.getEmail(), e.getMessage());
        }
    }
}

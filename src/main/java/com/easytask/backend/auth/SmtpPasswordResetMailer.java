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
    public void sendInvitation(AppUser user, String organizationName, String rawToken) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(user.getEmail());
        message.setSubject("You've been added to %s on EasyTask".formatted(organizationName));
        message.setText("""
                Hi %s,

                An administrator added you to %s on EasyTask.
                To activate your account, open the EasyTask app, choose
                "Forgot password?", and use this code to set your own password:

                    %s

                The code is valid for 7 days and can be used once.
                Your login email is this address.

                — EasyTask
                """.formatted(user.getFullName(), organizationName, rawToken));
        mailSender.send(message);
        log.info("Invitation email sent to {}", user.getEmail());
    }
}

package com.easytask.backend.auth;

import com.easytask.backend.user.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * Real mailer that sends account emails through Brevo's transactional HTTP API
 * (POST https://api.brevo.com/v3/smtp/email). Chosen over SMTP because our host
 * (Render) blocks outbound SMTP ports (25/465/587) — HTTPS on 443 is not
 * blocked. Active only when BREVO_API_KEY is configured; see
 * {@link com.easytask.backend.config.MailerConfiguration}.
 *
 * <p>The "from" address must be a Brevo-verified sender, otherwise the API
 * rejects the request; the sender email/name come from BREVO_SENDER_EMAIL /
 * BREVO_SENDER_NAME.
 */
public class BrevoPasswordResetMailer implements PasswordResetMailer {

    private static final Logger log = LoggerFactory.getLogger(BrevoPasswordResetMailer.class);
    private static final String SEND_URL = "https://api.brevo.com/v3/smtp/email";

    private final RestClient restClient;
    private final String senderEmail;
    private final String senderName;

    public BrevoPasswordResetMailer(RestClient restClient, String apiKey,
                                    String senderEmail, String senderName) {
        this.restClient = restClient.mutate()
                .baseUrl(SEND_URL)
                .defaultHeader("api-key", apiKey)
                .defaultHeader("accept", "application/json")
                .build();
        this.senderEmail = senderEmail;
        this.senderName = senderName;
    }

    @Override
    public void sendResetToken(AppUser user, String rawToken) {
        sendCritical(user, "EasyTask — password reset code", """
                Hi %s,

                Someone requested a password reset for your EasyTask account.
                Your reset code is:

                    %s

                Enter it in the app together with your new password. The code is
                valid for 30 minutes and can be used once.

                If you didn't request this, you can safely ignore this email.

                — EasyTask
                """.formatted(user.getFullName(), rawToken));
    }

    @Override
    public void sendInvitation(AppUser user, String organizationName, String rawToken) {
        sendCritical(user, "You've been added to %s on EasyTask".formatted(organizationName), """
                Hi %s,

                An administrator added you to %s on EasyTask.
                To activate your account, open the EasyTask app, choose
                "Forgot password?", and use this code to set your own password:

                    %s

                The code is valid for 7 days and can be used once.
                Your login email is this address.

                — EasyTask
                """.formatted(user.getFullName(), organizationName, rawToken));
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

    /** Reset/invite mails: a delivery failure must fail the operation (propagates). */
    private void sendCritical(AppUser user, String subject, String text) {
        try {
            dispatch(user, subject, text);
            log.info("Email '{}' sent to {} via Brevo", subject, user.getEmail());
        } catch (RestClientResponseException e) {
            // Surface the Brevo error body in the logs — e.g. unverified sender,
            // account under review — then propagate so the caller sees a failure.
            log.error("Brevo rejected email to {} ({}): {}", user.getEmail(),
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }
    }

    /** Notices are best-effort: a mail hiccup must never fail the operation. */
    private void sendNoticeQuietly(AppUser user, String subject, String text) {
        try {
            dispatch(user, subject, text);
            log.info("Security notice '{}' sent to {} via Brevo", subject, user.getEmail());
        } catch (Exception e) {
            log.warn("Could not send security notice to {}: {}", user.getEmail(), e.getMessage());
        }
    }

    private void dispatch(AppUser user, String subject, String text) {
        Map<String, Object> body = Map.of(
                "sender", Map.of("name", senderName, "email", senderEmail),
                "to", List.of(Map.of("email", user.getEmail(), "name", user.getFullName())),
                "subject", subject,
                "textContent", text);
        restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}

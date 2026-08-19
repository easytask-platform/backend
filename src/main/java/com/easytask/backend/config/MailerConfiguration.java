package com.easytask.backend.config;

import com.easytask.backend.auth.BrevoPasswordResetMailer;
import com.easytask.backend.auth.LoggingPasswordResetMailer;
import com.easytask.backend.auth.PasswordResetMailer;
import com.easytask.backend.auth.SmtpPasswordResetMailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Mailer selection. In priority order:
 * <ol>
 *   <li><b>Brevo HTTP API</b> when BREVO_API_KEY is set — the only option that
 *       works on hosts that block outbound SMTP ports (e.g. Render). Needs a
 *       Brevo-verified sender in BREVO_SENDER_EMAIL.</li>
 *   <li><b>SMTP</b> (D23) when SPRING_MAIL_PASSWORD is set — fine for local dev
 *       where SMTP egress is open.</li>
 *   <li><b>Logging</b> fallback so dev/demo setups work with zero config.</li>
 * </ol>
 */
@Configuration
public class MailerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MailerConfiguration.class);

    @Bean
    public PasswordResetMailer passwordResetMailer(
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${easytask.brevo.api-key:}") String brevoApiKey,
            @Value("${easytask.brevo.sender-email:}") String brevoSenderEmail,
            @Value("${easytask.brevo.sender-name:EasyTask}") String brevoSenderName,
            @Value("${spring.mail.username:}") String smtpFrom,
            @Value("${spring.mail.password:}") String smtpPassword) {

        if (StringUtils.hasText(brevoApiKey) && StringUtils.hasText(brevoSenderEmail)) {
            log.info("Using Brevo HTTP API mailer (sender {})", brevoSenderEmail);
            return new BrevoPasswordResetMailer(
                    RestClient.create(), brevoApiKey, brevoSenderEmail, brevoSenderName);
        }
        if (StringUtils.hasText(smtpPassword)) {
            log.info("Using SMTP mailer (from {})", smtpFrom);
            return new SmtpPasswordResetMailer(mailSender.getObject(), smtpFrom);
        }
        log.info("No mail credentials configured — using logging mailer (codes printed to log)");
        return new LoggingPasswordResetMailer();
    }
}

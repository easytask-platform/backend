package com.easytask.backend.config;

import com.easytask.backend.auth.LoggingPasswordResetMailer;
import com.easytask.backend.auth.PasswordResetMailer;
import com.easytask.backend.auth.SmtpPasswordResetMailer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Mailer selection (D23): SMTP when credentials are configured via env
 * (SPRING_MAIL_USERNAME / SPRING_MAIL_PASSWORD), otherwise the logging
 * fallback so dev and demo setups keep working with zero configuration.
 */
@Configuration
public class MailerConfiguration {

    @Bean
    @ConditionalOnProperty("spring.mail.password")
    public PasswordResetMailer smtpPasswordResetMailer(JavaMailSender mailSender,
                                                       @Value("${spring.mail.username}") String fromAddress) {
        return new SmtpPasswordResetMailer(mailSender, fromAddress);
    }

    @Bean
    @ConditionalOnMissingBean(PasswordResetMailer.class)
    public PasswordResetMailer loggingPasswordResetMailer() {
        return new LoggingPasswordResetMailer();
    }
}

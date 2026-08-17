package com.easytask.backend.auth;

import com.easytask.backend.user.AppUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpPasswordResetMailerTest {

    @Test
    void sendsResetCodeToTheUsersAddressFromTheConfiguredAccount() {
        JavaMailSender sender = mock(JavaMailSender.class);
        SmtpPasswordResetMailer mailer = new SmtpPasswordResetMailer(sender, "mailer@easytask.test");
        AppUser user = AppUser.builder()
                .fullName("Sam Employee")
                .email("sam@acme.test")
                .build();

        mailer.sendResetToken(user, "RAW-CODE-123");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getFrom()).isEqualTo("mailer@easytask.test");
        assertThat(message.getTo()).containsExactly("sam@acme.test");
        assertThat(message.getSubject()).contains("password reset");
        assertThat(message.getText()).contains("Sam Employee").contains("RAW-CODE-123");
    }
}

package com.fitback.backend.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.member.config.PasswordResetProperties;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class PasswordResetMailSenderTest {

    @Mock
    private JavaMailSender mailSender;

    private MimeMessage message;
    private PasswordResetMailSender passwordResetMailSender;

    @BeforeEach
    void setUp() {
        message = new MimeMessage(Session.getInstance(new Properties()));

        PasswordResetProperties properties = new PasswordResetProperties(
                "http://localhost:3000/reset-password",
                "sender@fitback.com",
                Duration.ofMinutes(5),
                Duration.ofMinutes(1)
        );
        passwordResetMailSender = new PasswordResetMailSender(mailSender, properties);
    }

    @Test
    void sendsHtmlMailWithResetTokenOnlyInButtonUrl() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(message);

        passwordResetMailSender.sendResetLink(
                "member@fitback.com",
                "reset-token"
        );

        verify(mailSender).send(message);
        assertThat(message.getFrom()[0].toString()).isEqualTo("FITBACK <sender@fitback.com>");
        assertThat(message.getRecipients(Message.RecipientType.TO)[0].toString())
                .isEqualTo("member@fitback.com");
        assertThat(message.getSubject()).isEqualTo("[FITBACK] 비밀번호 재설정 링크 안내");

        String html = message.getContent().toString();
        assertThat(html)
                .contains(
                        "href=\"http://localhost:3000/reset-password"
                                + "?resetToken=reset-token\""
                )
                .contains("비밀번호 재설정하기")
                .containsOnlyOnce("reset-token");
    }

    @Test
    void ignoresMailFailureToProtectAccountExistence() {
        when(mailSender.createMimeMessage()).thenReturn(message);
        doThrow(new MailSendException("mail failure"))
                .when(mailSender)
                .send(any(MimeMessage.class));

        assertThatCode(() ->
                passwordResetMailSender.sendResetLink(
                        "member@fitback.com",
                        "reset-token"
                )
        ).doesNotThrowAnyException();
    }

    @Test
    void ignoresInvalidResetUrlToProtectAccountExistence() {
        PasswordResetProperties invalidProperties = mock(PasswordResetProperties.class);
        when(invalidProperties.frontendUrl()).thenReturn("http://[invalid");
        PasswordResetMailSender mailSenderWithInvalidUrl =
                new PasswordResetMailSender(mailSender, invalidProperties);

        assertThatCode(() ->
                mailSenderWithInvalidUrl.sendResetLink(
                        "member@fitback.com",
                        "reset-token"
                )
        ).doesNotThrowAnyException();
    }
}

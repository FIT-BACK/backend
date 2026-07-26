package com.fitback.backend.domain.member.service;

import com.fitback.backend.domain.member.config.PasswordResetProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@Slf4j
@RequiredArgsConstructor
public class PasswordResetMailSender {

    private static final String SUBJECT = "[FITBACK] 비밀번호 재설정 안내";

    private final JavaMailSender mailSender;
    private final PasswordResetProperties properties;

    public void sendResetLink(String recipientEmail, String resetToken) {
        //재설정 토큰을 프론트엔드 주소의 쿼리 파라미터로 추가
        String resetUrl = UriComponentsBuilder
                .fromUriString(properties.frontendUrl())
                .queryParam("resetToken", resetToken)
                .build()
                .encode()
                .toUriString();

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");

            helper.setFrom(properties.senderEmail());
            helper.setTo(recipientEmail);
            helper.setSubject(SUBJECT);
            helper.setText(createHtml(resetUrl), true);

            //재설정 토큰은 HTML 버튼 주소에만 포함
            mailSender.send(message);
        } catch (MessagingException | MailException exception) {
            //가입 여부 노출 방지를 위해 메일 전송 실패도 성공 처리
            log.error("비밀번호 재설정 메일 전송 실패", exception);
        }
    }

    private String createHtml(String resetUrl) {
        return """
                <!DOCTYPE html>
                <html lang="ko">
                <body>
                    <h2>비밀번호 재설정</h2>
                    <p>아래 버튼을 눌러 새 비밀번호를 설정해주세요.</p>
                    <a href="%s"
                       style="display:inline-block;padding:12px 20px;
                              background:#7c6ee6;color:#ffffff;
                              text-decoration:none;border-radius:6px;">
                        비밀번호 재설정
                    </a>
                    <p>요청하지 않았다면 이 메일을 무시해주세요.</p>
                </body>
                </html>
                """.formatted(resetUrl);
    }
}

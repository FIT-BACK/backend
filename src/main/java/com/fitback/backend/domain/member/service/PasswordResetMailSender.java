package com.fitback.backend.domain.member.service;

import com.fitback.backend.domain.member.config.PasswordResetProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
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

    private static final String SUBJECT = "[FITBACK] 비밀번호 재설정 링크 안내";
    private static final String SENDER_NAME = "FITBACK";

    private final JavaMailSender mailSender;
    private final PasswordResetProperties properties;

    public void sendResetLink(String recipientEmail, String resetToken) {
        //재설정 토큰은 프론트엔드 주소의 쿼리 파라미터로 추가
        String resetUrl = UriComponentsBuilder
                .fromUriString(properties.frontendUrl())
                .queryParam("resetToken", resetToken)
                .build()
                .encode()
                .toUriString();

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");

            helper.setFrom(properties.senderEmail(), SENDER_NAME);
            helper.setTo(recipientEmail);
            helper.setSubject(SUBJECT);
            helper.setText(createHtml(resetUrl), true);

            //재설정 토큰은 HTML 버튼 주소에만 포함
            mailSender.send(message);
        } catch (MessagingException | MailException | UnsupportedEncodingException exception) {
            //가입 여부 노출 방지를 위해 메일 전송 실패도 성공 처리
            log.error("비밀번호 재설정 메일 전송 실패", exception);
        }
    }

    private String createHtml(String resetUrl) {
        return """
                <!DOCTYPE html>
                <html lang="ko">
                <body style="margin:0;padding:0;background:#f6f7fb;">
                    <div style="max-width:520px;margin:0 auto;padding:32px 20px;
                                font-family:Arial,'Apple SD Gothic Neo','Malgun Gothic',sans-serif;
                                color:#222222;">
                        <div style="background:#ffffff;border:1px solid #ececf3;
                                    border-radius:8px;padding:28px;">
                            <h2 style="margin:0 0 16px;font-size:22px;line-height:1.4;">
                                비밀번호 재설정 안내
                            </h2>
                            <p style="margin:0 0 12px;font-size:15px;line-height:1.7;">
                                FITBACK 계정의 비밀번호 재설정 요청이 접수되었습니다.
                            </p>
                            <p style="margin:0 0 24px;font-size:15px;line-height:1.7;">
                                아래 버튼을 눌러 새 비밀번호를 설정해주세요.
                            </p>
                            <a href="%s"
                               style="display:inline-block;padding:13px 22px;
                                      background:#7c6ee6;color:#ffffff;
                                      text-decoration:none;border-radius:6px;
                                      font-size:15px;font-weight:700;">
                                비밀번호 재설정하기
                            </a>
                            <p style="margin:24px 0 0;font-size:13px;line-height:1.6;color:#666666;">
                                이 링크는 발급 후 5분 동안 사용할 수 있습니다.
                                직접 요청하지 않았다면 이 메일을 무시해주세요.
                            </p>
                        </div>
                    </div>
                </body>
                </html>
                """.formatted(resetUrl);
    }
}

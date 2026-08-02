package com.fitback.backend.domain.member.service;

import com.fitback.backend.domain.member.config.PasswordResetProperties;
import com.fitback.backend.domain.member.dto.MemberRequest;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.entity.PasswordResetToken;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.member.repository.PasswordResetTokenRepository;
import com.fitback.backend.domain.member.util.PasswordResetTokenUtil;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.util.LowercaseNormalizer;
import com.fitback.backend.global.validation.BCryptPasswordPolicy;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final MemberRepository memberRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordResetTokenUtil passwordResetTokenUtil;
    private final PasswordResetMailSender passwordResetMailSender;
    private final PasswordResetProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    //비밀번호 재설정 링크 전송 API 서비스 메서드
    public void requestResetLink(MemberRequest.PasswordResetLinkRequest dto) {
        //토큰 저장 트랜잭션 완료 후 메일 전송
        ResetMailTarget mailTarget = transactionTemplate.execute(
                status -> issueResetToken(dto.email())
        );

        if (mailTarget == null) {
            return;
        }
        passwordResetMailSender.sendResetLink(
                mailTarget.email(),
                mailTarget.resetToken()
        );
    }

    //비밀번호 재설정 API 서비스 메서드
    @Transactional
    public void resetPassword(MemberRequest.PasswordResetRequest dto) {
        BCryptPasswordPolicy.validate(dto.newPassword());
        //전달받은 토큰을 DB 조회용 해시값으로 변환
        String tokenHash;
        try {
            tokenHash = passwordResetTokenUtil.hash(dto.resetToken());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD_RESET_TOKEN);
        }

        //동일 토큰의 중복 사용 방지를 위한 잠금 조회
        PasswordResetToken storedToken =
                passwordResetTokenRepository.findByTokenHashForUpdate(tokenHash)
                        .orElseThrow(() -> new BusinessException(
                                ErrorCode.INVALID_PASSWORD_RESET_TOKEN
                        ));

        //만료된 토큰은 비밀번호 변경 불가
        if (storedToken.isExpired(LocalDateTime.now(clock))) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD_RESET_TOKEN);
        }

        Member member = storedToken.getMember();
        member.changePassword(passwordEncoder.encode(dto.newPassword()));
        member.clearRefreshToken();

        //사용 완료된 일회용 토큰 삭제
        passwordResetTokenRepository.delete(storedToken);
    }

    private ResetMailTarget issueResetToken(String email) {
        String normalizedEmail = LowercaseNormalizer.normalize(email);

        //존재하지 않거나 소셜 회원이면 동일 성공 처리
        Member member = memberRepository.findByEmailForUpdate(normalizedEmail)
                .orElse(null);
        if (member == null || member.getLoginProvider() != LoginProvider.EMAIL) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        PasswordResetToken existingToken = passwordResetTokenRepository
                .findById(member.getId())
                .orElse(null);

        //재요청 대기 시간에는 기존 토큰과 메일을 유지
        if (existingToken != null
                && existingToken.isReissueBlocked(now, properties.requestCooldown())) {
            return null;
        }

        //재요청 대기 시간이 지나면 기존 토큰을 교체
        if (existingToken != null) {
            passwordResetTokenRepository.delete(existingToken);
            passwordResetTokenRepository.flush();
        }

        PasswordResetTokenUtil.GeneratedToken generatedToken =
                passwordResetTokenUtil.generate();
        LocalDateTime expiresAt = now.plus(properties.tokenTtl());
        PasswordResetToken newToken = PasswordResetToken.create(
                member,
                generatedToken.tokenHash(),
                expiresAt
        );
        passwordResetTokenRepository.saveAndFlush(newToken);

        return new ResetMailTarget(member.getEmail(), generatedToken.resetToken());
    }

    private record ResetMailTarget(
            String email,
            String resetToken
    ) {
    }
}

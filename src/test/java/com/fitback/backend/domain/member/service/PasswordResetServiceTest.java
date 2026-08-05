package com.fitback.backend.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-26T10:00:00Z"),
            ZoneOffset.UTC
    );

    @Mock
    private MemberRepository memberRepository;
    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock
    private PasswordResetTokenUtil passwordResetTokenUtil;
    @Mock
    private PasswordResetMailSender passwordResetMailSender;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private LoginAttemptService loginAttemptService;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transactionStatus;

    private PasswordResetService passwordResetService;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(transactionStatus);

        PasswordResetProperties properties = new PasswordResetProperties(
                "http://localhost:3000/reset-password",
                "test@fitback.com",
                Duration.ofMinutes(5),
                Duration.ofMinutes(1)
        );
        passwordResetService = new PasswordResetService(
                memberRepository,
                passwordResetTokenRepository,
                passwordResetTokenUtil,
                passwordResetMailSender,
                properties,
                passwordEncoder,
                loginAttemptService,
                new TransactionTemplate(transactionManager),
                FIXED_CLOCK
        );
    }

    @Test
    void requestResetLinkReplacesTokenAndSendsMailAfterCommit() {
        Member member = createMember(1L, "member@fitback.com", LoginProvider.EMAIL);
        PasswordResetToken existingToken = PasswordResetToken.create(
                member,
                "a".repeat(64),
                LocalDateTime.of(2026, 7, 26, 10, 1)
        );
        ReflectionTestUtils.setField(
                existingToken,
                "createdAt",
                LocalDateTime.of(2026, 7, 26, 9, 58)
        );
        PasswordResetTokenUtil.GeneratedToken generatedToken =
                new PasswordResetTokenUtil.GeneratedToken(
                        "reset-token",
                        "b".repeat(64)
                );

        when(memberRepository.findByEmailForUpdate("member@fitback.com"))
                .thenReturn(Optional.of(member));
        when(passwordResetTokenUtil.generate()).thenReturn(generatedToken);
        when(passwordResetTokenRepository.findById(member.getId()))
                .thenReturn(Optional.of(existingToken));
        when(passwordResetTokenRepository.saveAndFlush(any(PasswordResetToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        passwordResetService.requestResetLink(
                new MemberRequest.PasswordResetLinkRequest("  Member@FITBACK.COM  ")
        );

        ArgumentCaptor<PasswordResetToken> tokenCaptor =
                ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).saveAndFlush(tokenCaptor.capture());
        PasswordResetToken savedToken = tokenCaptor.getValue();
        assertThat(savedToken.getMember()).isSameAs(member);
        assertThat(savedToken.getTokenHash()).isEqualTo("b".repeat(64));
        assertThat(savedToken.getExpiresAt())
                .isEqualTo(LocalDateTime.of(2026, 7, 26, 10, 5));
        verify(passwordResetTokenRepository).delete(existingToken);
        verify(passwordResetTokenRepository).flush();

        InOrder order = inOrder(transactionManager, passwordResetMailSender);
        order.verify(transactionManager).commit(transactionStatus);
        order.verify(passwordResetMailSender)
                .sendResetLink("member@fitback.com", "reset-token");
    }

    @Test
    void requestResetLinkKeepsExistingTokenDuringCooldown() {
        Member member = createMember(1L, "member@fitback.com", LoginProvider.EMAIL);
        PasswordResetToken existingToken = PasswordResetToken.create(
                member,
                "a".repeat(64),
                LocalDateTime.of(2026, 7, 26, 10, 4)
        );
        ReflectionTestUtils.setField(
                existingToken,
                "createdAt",
                LocalDateTime.of(2026, 7, 26, 9, 59, 30)
        );

        when(memberRepository.findByEmailForUpdate("member@fitback.com"))
                .thenReturn(Optional.of(member));
        when(passwordResetTokenRepository.findById(member.getId()))
                .thenReturn(Optional.of(existingToken));

        passwordResetService.requestResetLink(
                new MemberRequest.PasswordResetLinkRequest("member@fitback.com")
        );

        verify(passwordResetTokenUtil, never()).generate();
        verify(passwordResetTokenRepository, never()).delete(any());
        verify(passwordResetTokenRepository, never()).saveAndFlush(any());
        verify(passwordResetMailSender, never()).sendResetLink(any(), any());
    }

    @Test
    void requestResetLinkIgnoresUnknownEmail() {
        when(memberRepository.findByEmailForUpdate("unknown@fitback.com"))
                .thenReturn(Optional.empty());

        passwordResetService.requestResetLink(
                new MemberRequest.PasswordResetLinkRequest("Unknown@FITBACK.COM")
        );

        verify(passwordResetTokenUtil, never()).generate();
        verify(passwordResetTokenRepository, never()).saveAndFlush(any());
        verify(passwordResetMailSender, never()).sendResetLink(any(), any());
    }

    @Test
    void requestResetLinkIgnoresSocialMember() {
        Member member = createMember(1L, "social@fitback.com", LoginProvider.KAKAO);
        when(memberRepository.findByEmailForUpdate("social@fitback.com"))
                .thenReturn(Optional.of(member));

        passwordResetService.requestResetLink(
                new MemberRequest.PasswordResetLinkRequest("social@fitback.com")
        );

        verify(passwordResetTokenUtil, never()).generate();
        verify(passwordResetTokenRepository, never()).saveAndFlush(any());
        verify(passwordResetMailSender, never()).sendResetLink(any(), any());
    }

    @Test
    void resetPasswordChangesPasswordClearsRefreshTokenAndDeletesToken() {
        Member member = createMember(1L, "member@fitback.com", LoginProvider.EMAIL);
        member.updateRefreshToken("refresh-token");
        PasswordResetToken storedToken = PasswordResetToken.create(
                member,
                "c".repeat(64),
                LocalDateTime.of(2026, 7, 26, 10, 1)
        );

        when(passwordResetTokenUtil.hash("reset-token")).thenReturn("c".repeat(64));
        when(passwordResetTokenRepository.findByTokenHashForUpdate("c".repeat(64)))
                .thenReturn(Optional.of(storedToken));
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-new-password");

        passwordResetService.resetPassword(
                new MemberRequest.PasswordResetRequest(
                        "reset-token",
                        "new-password"
                )
        );

        assertThat(member.getPassword()).isEqualTo("encoded-new-password");
        assertThat(member.getRefreshToken()).isNull();
        verify(passwordResetTokenRepository).delete(storedToken);
        verify(loginAttemptService).clear("member@fitback.com");
    }

    @Test
    void resetPasswordRejectsPasswordOverBcryptByteLimitBeforeEncoding() {
        MemberRequest.PasswordResetRequest request = new MemberRequest.PasswordResetRequest(
                "reset-token",
                "가".repeat(25)
        );

        assertThatThrownBy(() -> passwordResetService.resetPassword(request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR)
                );

        verify(passwordEncoder, never()).encode(any());
        verify(passwordResetTokenUtil, never()).hash(any());
    }

    @Test
    void resetPasswordRejectsExpiredToken() {
        Member member = createMember(1L, "member@fitback.com", LoginProvider.EMAIL);
        PasswordResetToken expiredToken = PasswordResetToken.create(
                member,
                "d".repeat(64),
                LocalDateTime.of(2026, 7, 26, 10, 0)
        );

        when(passwordResetTokenUtil.hash("expired-token")).thenReturn("d".repeat(64));
        when(passwordResetTokenRepository.findByTokenHashForUpdate("d".repeat(64)))
                .thenReturn(Optional.of(expiredToken));

        assertThatThrownBy(() ->
                passwordResetService.resetPassword(
                        new MemberRequest.PasswordResetRequest(
                                "expired-token",
                                "new-password"
                        )
                )
        ).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_PASSWORD_RESET_TOKEN)
        );

        verify(passwordEncoder, never()).encode(any());
        verify(passwordResetTokenRepository, never()).delete(any());
        verify(loginAttemptService, never()).clear(any());
    }

    @Test
    void resetPasswordRejectsUnknownToken() {
        when(passwordResetTokenUtil.hash("unknown-token")).thenReturn("e".repeat(64));
        when(passwordResetTokenRepository.findByTokenHashForUpdate("e".repeat(64)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                passwordResetService.resetPassword(
                        new MemberRequest.PasswordResetRequest(
                                "unknown-token",
                                "new-password"
                        )
                )
        ).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_PASSWORD_RESET_TOKEN)
        );
    }

    private Member createMember(Long id, String email, LoginProvider loginProvider) {
        Member member = loginProvider == LoginProvider.EMAIL
                ? Member.create(email, "member", "encoded-password", loginProvider)
                : Member.createSocial(email, "member", loginProvider, "social-uid");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }
}

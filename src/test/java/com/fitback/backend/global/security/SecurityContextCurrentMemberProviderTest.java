package com.fitback.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SecurityContextCurrentMemberProviderTest {

    @Mock
    private MemberRepository memberRepository;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesMemberIdFromAuthenticatedEmail() {
        Member member = Member.create(
                "member@example.com",
                "주녁",
                "password",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(member, "id", 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "member@example.com",
                        null,
                        List.of()
                )
        );
        when(memberRepository.findByEmail("member@example.com"))
                .thenReturn(Optional.of(member));
        SecurityContextCurrentMemberProvider provider =
                new SecurityContextCurrentMemberProvider(memberRepository);

        assertThat(provider.getCurrentMemberId()).isEqualTo(1L);
    }

    @Test
    void rejectsRequestWithoutAuthentication() {
        SecurityContextCurrentMemberProvider provider =
                new SecurityContextCurrentMemberProvider(memberRepository);

        assertThatThrownBy(provider::getCurrentMemberId)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }
}

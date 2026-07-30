package com.fitback.backend.domain.member.init;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.member.service.MemberService;
import com.fitback.backend.global.security.entity.AuthMember;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;

class PrototypeSmokeAccountCleanupTest {

    @Test
    void removesOnlyTheKnownStaleSmokeAccount() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        MemberService memberService = mock(MemberService.class);
        Member member = Member.create(
                PrototypeSmokeAccountCleanup.STALE_ACCOUNT_EMAIL,
                "p0-smoke",
                "encoded-password",
                LoginProvider.EMAIL
        );
        when(memberRepository.findByEmail(PrototypeSmokeAccountCleanup.STALE_ACCOUNT_EMAIL))
                .thenReturn(Optional.of(member));
        when(memberRepository.existsByEmail(PrototypeSmokeAccountCleanup.STALE_ACCOUNT_EMAIL))
                .thenReturn(false);
        PrototypeSmokeAccountCleanup cleanup =
                new PrototypeSmokeAccountCleanup(memberRepository, memberService);

        cleanup.run(mock(ApplicationArguments.class));

        ArgumentCaptor<AuthMember> captor = ArgumentCaptor.forClass(AuthMember.class);
        verify(memberService).deleteAccount(captor.capture());
        assertThat(captor.getValue().getMember()).isSameAs(member);
        verify(memberRepository).existsByEmail(PrototypeSmokeAccountCleanup.STALE_ACCOUNT_EMAIL);
    }
}

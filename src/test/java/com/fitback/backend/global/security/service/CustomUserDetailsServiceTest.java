package com.fitback.backend.global.security.service;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.global.security.entity.AuthMember;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private CustomUserDetailsService customUserDetailsService;

    //인증 username도 소문자 이메일 기준으로 회원 조회
    @Test
    void loadUserByUsernameNormalizeEmailTest() {
        Member member = Member.create("test@fitback.com", "test_user", "encodedPw", LoginProvider.EMAIL);
        when(memberRepository.findByEmail("test@fitback.com")).thenReturn(Optional.of(member));

        AuthMember authMember = (AuthMember) customUserDetailsService.loadUserByUsername("Test@FITBACK.COM");

        assertThat(authMember.getUsername()).isEqualTo("test@fitback.com");
        verify(memberRepository).findByEmail("test@fitback.com");
    }
}

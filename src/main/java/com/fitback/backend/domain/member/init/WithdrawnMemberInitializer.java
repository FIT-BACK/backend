package com.fitback.backend.domain.member.init;

import com.fitback.backend.domain.member.constant.WithdrawnMember;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

//앱 기동 시 탈퇴 회원 계정이 없으면 생성 (멱등)
@Component
@RequiredArgsConstructor
public class WithdrawnMemberInitializer implements ApplicationRunner {

    private final MemberRepository memberRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        boolean existsByEmail = memberRepository.existsByEmail(WithdrawnMember.EMAIL);
        boolean existsByNickname = memberRepository.existsByNickname(WithdrawnMember.NICKNAME);

        if (existsByEmail && existsByNickname) {
            return;
        }

        if (existsByEmail || existsByNickname) {
            throw new IllegalStateException("Withdrawn member account is inconsistent.");
        }

        //password=null → 이메일 로그인 원천 차단
        memberRepository.save(
                Member.create(WithdrawnMember.EMAIL, WithdrawnMember.NICKNAME, null, LoginProvider.EMAIL));
    }
}

package com.fitback.backend.domain.member.init;

import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.member.service.MemberService;
import com.fitback.backend.global.security.entity.AuthMember;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
@RequiredArgsConstructor
public class PrototypeSmokeAccountCleanup implements ApplicationRunner {

    static final String STALE_ACCOUNT_EMAIL =
            "p0-smoke-1785410082421-9ebdcfe3b2bf5bb2@fitback.test";

    private final MemberRepository memberRepository;
    private final MemberService memberService;

    @Override
    public void run(ApplicationArguments args) {
        memberRepository.findByEmail(STALE_ACCOUNT_EMAIL)
                .ifPresent(member -> memberService.deleteAccount(new AuthMember(member)));

        if (memberRepository.existsByEmail(STALE_ACCOUNT_EMAIL)) {
            throw new IllegalStateException("Stale prototype smoke account cleanup failed.");
        }
    }
}

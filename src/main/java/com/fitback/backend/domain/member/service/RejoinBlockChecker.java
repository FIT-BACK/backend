package com.fitback.backend.domain.member.service;

import com.fitback.backend.domain.member.repository.WithdrawnEmailBlockRepository;
import com.fitback.backend.global.util.HmacUtil;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

//탈퇴 후 30일 재가입 차단 검사 (이메일·카카오 가입 공용)
//검사 결과만 반환 — 예외 타입은 호출부 컨텍스트에 맞게 각자 던짐
@Component
@RequiredArgsConstructor
public class RejoinBlockChecker {

    private final WithdrawnEmailBlockRepository withdrawnEmailBlockRepository;
    private final HmacUtil hmacUtil;

    //탈퇴 후 30일 재가입 차단 대상 이메일인지 여부
    public boolean isRejoinBlocked(String email) {
        String hashedEmail = hmacUtil.hashHex(email);
        return withdrawnEmailBlockRepository.existsByEmailHashAndBlockedUntilAfter(hashedEmail, LocalDateTime.now());
    }
}

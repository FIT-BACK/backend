package com.fitback.backend.domain.member.service;

import com.fitback.backend.domain.member.repository.WithdrawalEmailBlockRepository;
import com.fitback.backend.global.util.HmacUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RejoinBlockCheckerTest {

    @Mock
    private WithdrawalEmailBlockRepository withdrawalEmailBlockRepository;
    @Mock
    private HmacUtil hmacUtil;

    //재가입 차단 검사는 정규화된 이메일 기준으로 해시 생성
    @Test
    void isRejoinBlockedNormalizeEmailBeforeHashTest() {
        RejoinBlockChecker checker = new RejoinBlockChecker(withdrawalEmailBlockRepository, hmacUtil);
        when(hmacUtil.hashHex("test@fitback.com")).thenReturn("hashed-email");

        checker.isRejoinBlocked("Test@FITBACK.COM");

        verify(hmacUtil).hashHex("test@fitback.com");
        verify(withdrawalEmailBlockRepository)
                .existsByEmailHashAndBlockedUntilAfter(any(), any());
    }
}

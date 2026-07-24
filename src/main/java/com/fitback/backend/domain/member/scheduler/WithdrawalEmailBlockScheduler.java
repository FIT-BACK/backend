package com.fitback.backend.domain.member.scheduler;

import com.fitback.backend.domain.member.repository.WithdrawalEmailBlockRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class WithdrawalEmailBlockScheduler {

    private final WithdrawalEmailBlockRepository withdrawalEmailBlockRepository;

    //매일 새벽 4시 만료된 재가입 차단 기록 삭제
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void purgeExpired() {
        withdrawalEmailBlockRepository.deleteExpired(LocalDateTime.now());
    }
}

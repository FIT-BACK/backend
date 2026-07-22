package com.fitback.backend.domain.member.scheduler;

import com.fitback.backend.domain.member.repository.WithdrawnEmailBlockRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class WithdrawnEmailBlockScheduler {

    private final WithdrawnEmailBlockRepository withdrawnEmailBlockRepository;

    //매일 새벽 4시, 만료된 재가입 차단 기록 삭제 (개인정보 조기 파기)
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void purgeExpired() {
        withdrawnEmailBlockRepository.deleteExpired(LocalDateTime.now());
    }
}

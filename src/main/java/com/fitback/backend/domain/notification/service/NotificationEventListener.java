package com.fitback.backend.domain.notification.service;

import com.fitback.backend.domain.notification.event.AnalysisCompletedEvent;
import com.fitback.backend.domain.notification.event.LookbookLikedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

//원본 작업 커밋 이후 알림 생성 위임
//알림 실패가 원본 요청 응답을 깨지 않도록 예외를 여기서 차단
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationCreateService notificationCreateService;

    //좋아요 저장이 확정된 뒤에만 실행
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleLookbookLiked(LookbookLikedEvent event) {
        try {
            notificationCreateService.createLookbookLikedNotification(event);
        } catch (RuntimeException exception) {
            log.error(
                    "Lookbook liked notification creation failed. lookbookId={}, recipientMemberId={}, failureType={}",
                    event.lookbookId(),
                    event.recipientMemberId(),
                    exception.getClass().getSimpleName(),
                    exception
            );
        }
    }

    //리포트 저장이 확정된 뒤에만 실행
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleAnalysisCompleted(AnalysisCompletedEvent event) {
        try {
            notificationCreateService.createAnalysisCompletedNotification(event);
        } catch (RuntimeException exception) {
            log.error(
                    "Analysis completed notification creation failed. reportId={}, recipientMemberId={}, failureType={}",
                    event.reportId(),
                    event.recipientMemberId(),
                    exception.getClass().getSimpleName(),
                    exception
            );
        }
    }
}

package com.fitback.backend.domain.notification.service;

import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.notification.entity.MemberNotificationSetting;
import com.fitback.backend.domain.notification.entity.Notification;
import com.fitback.backend.domain.notification.entity.NotificationType;
import com.fitback.backend.domain.notification.event.AnalysisCompletedEvent;
import com.fitback.backend.domain.notification.event.LookbookLikedEvent;
import com.fitback.backend.domain.notification.repository.NotificationRepository;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

//도메인 이벤트를 받아 알림 저장 전담 (조회·읽음·삭제는 NotificationService)
@Service
@RequiredArgsConstructor
public class NotificationCreateService {

    //알림 문구 (변경 요청 시 이 상수만 수정)
    private static final String LOOKBOOK_LIKED_TITLE = "룩북에 좋아요가 눌렸어요";
    private static final String LOOKBOOK_LIKED_BODY_FORMAT = "%s님이 내 룩북을 좋아해요 ❤️";
    private static final String ANALYSIS_COMPLETE_TITLE = "AI 분석이 완료됐어요";
    private static final String ANALYSIS_COMPLETE_BODY = "요청하신 스타일 분석과 추천 결과 확인이 가능합니다.";

    private final NotificationRepository notificationRepository;
    private final NotificationSettingService notificationSettingService;
    private final MemberRepository memberRepository;

    //룩북 좋아요 알림 생성 (NotificationEventListener가 커밋 이후 호출)
    //AFTER_COMMIT 시점에도 원본 트랜잭션 자원이 스레드에 남아 있어
    //기본 propagation으로는 이미 커밋된 트랜잭션에 합류해 저장이 유실되므로 REQUIRES_NEW 필수
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createLookbookLikedNotification(LookbookLikedEvent event) {

        //본인 룩북에 본인이 누른 좋아요는 알림 대상 아님
        if (Objects.equals(event.recipientMemberId(), event.actorMemberId())) {
            return;
        }

        //알림 행의 FK로만 사용하므로 프록시 참조로 회원 조회 쿼리 생략
        Member recipient = memberRepository.getReferenceById(event.recipientMemberId());

        //수신자가 좋아요 알림을 끈 경우 저장하지 않음 (설정 값이 없으면 기본값 생성 후 판단)
        MemberNotificationSetting setting = notificationSettingService.getOrCreateSetting(recipient);
        if (!setting.getLookbookLikedEnabled()) {
            return;
        }

        //대상 ID 세 칸 중 유형에 맞는 것만 채우고 나머지는 null 유지
        //채운 칸이 목록 조회 시 targetId로 변환되므로 비우면 프론트 화면 이동이 끊김
        notificationRepository.save(Notification.create(
                recipient,                        //member - 알림 수신자
                NotificationType.LOOKBOOK_LIKED,  //notificationType
                event.actorMemberId(),            //actorMemberId - 좋아요를 누른 회원
                event.lookbookId(),               //lookbookId - 룩북 상세 딥링크 대상
                null,                             //reportId - 좋아요 알림에는 해당 없음
                null,                             //trendId - 좋아요 알림에는 해당 없음
                LOOKBOOK_LIKED_TITLE,             //title
                LOOKBOOK_LIKED_BODY_FORMAT.formatted(event.actorNickname())  //body
        ));
    }

    //AI 분석 완료 알림 생성 (NotificationEventListener가 커밋 이후 호출)
    //REQUIRES_NEW가 필요한 이유는 createLookbookLikedNotification 주석 참고
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createAnalysisCompletedNotification(AnalysisCompletedEvent event) {

        //수신자는 분석을 요청한 본인이므로 자기 알림 제외 분기 없음
        Member recipient = memberRepository.getReferenceById(event.recipientMemberId());

        //수신자가 분석 완료 알림을 끈 경우 저장하지 않음
        MemberNotificationSetting setting = notificationSettingService.getOrCreateSetting(recipient);
        if (!setting.getAnalysisCompleteEnabled()) {
            return;
        }

        //대상 ID 세 칸 중 유형에 맞는 것만 채우고 나머지는 null 유지
        //채운 칸이 목록 조회 시 targetId로 변환되므로 비우면 프론트 화면 이동이 끊김
        notificationRepository.save(Notification.create(
                recipient,                           //member - 알림 수신자
                NotificationType.ANALYSIS_COMPLETE,  //notificationType
                null,                                //actorMemberId - 시스템 알림이라 유발한 회원 없음
                null,                                //lookbookId - 분석 알림에는 해당 없음
                event.reportId(),                    //reportId - 분석 결과 딥링크 대상
                null,                                //trendId - 분석 알림에는 해당 없음
                ANALYSIS_COMPLETE_TITLE,             //title
                ANALYSIS_COMPLETE_BODY               //body
        ));
    }
}

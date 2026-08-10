package com.fitback.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.notification.entity.MemberNotificationSetting;
import com.fitback.backend.domain.notification.entity.Notification;
import com.fitback.backend.domain.notification.entity.NotificationType;
import com.fitback.backend.domain.notification.event.AnalysisCompletedEvent;
import com.fitback.backend.domain.notification.event.LookbookLikedEvent;
import com.fitback.backend.domain.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class NotificationCreateServiceTest {

    private static final Long RECIPIENT_ID = 1L;
    private static final Long ACTOR_ID = 2L;
    private static final Long LOOKBOOK_ID = 10L;
    private static final Long REPORT_ID = 20L;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationSettingService notificationSettingService;

    @Mock
    private MemberRepository memberRepository;

    private NotificationCreateService notificationCreateService;
    private Member recipient;

    @BeforeEach
    void setUp() {
        notificationCreateService = new NotificationCreateService(
                notificationRepository,
                notificationSettingService,
                memberRepository
        );
        recipient = createTestMember(RECIPIENT_ID);
    }

    //실제 db를 안 쓰므로 회원 id 강제 세팅
    private Member createTestMember(Long id) {
        Member testMember = Member.create(
                "recipient@fitback.com",
                "recipient",
                "encodedPw",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(testMember, "id", id);
        return testMember;
    }

    private ArgumentCaptor<Notification> captureSavedNotification() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        return captor;
    }

    @Test
    void createLookbookLikedNotificationTest() {
        when(memberRepository.getReferenceById(RECIPIENT_ID)).thenReturn(recipient);
        when(notificationSettingService.getOrCreateSetting(recipient))
                .thenReturn(MemberNotificationSetting.createDefault(recipient));

        notificationCreateService.createLookbookLikedNotification(new LookbookLikedEvent(
                LOOKBOOK_ID,
                RECIPIENT_ID,
                ACTOR_ID,
                "minji"
        ));

        Notification saved = captureSavedNotification().getValue();
        assertThat(saved.getMember()).isSameAs(recipient);
        assertThat(saved.getNotificationType()).isEqualTo(NotificationType.LOOKBOOK_LIKED);
        assertThat(saved.getActorMemberId()).isEqualTo(ACTOR_ID);
        assertThat(saved.getBody()).contains("minji");
        //룩북 상세 딥링크가 끊기지 않도록 lookbookId만 채워야 함
        assertThat(saved.getLookbookId()).isEqualTo(LOOKBOOK_ID);
        assertThat(saved.getReportId()).isNull();
        assertThat(saved.getTrendId()).isNull();
    }

    @Test
    void createLookbookLikedNotificationSkipsSelfLikeTest() {
        notificationCreateService.createLookbookLikedNotification(new LookbookLikedEvent(
                LOOKBOOK_ID,
                RECIPIENT_ID,
                RECIPIENT_ID,
                "recipient"
        ));

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void createLookbookLikedNotificationSkipsWhenSettingDisabledTest() {
        MemberNotificationSetting setting = MemberNotificationSetting.createDefault(recipient);
        setting.changeLookbookLikedEnabled(false);
        when(memberRepository.getReferenceById(RECIPIENT_ID)).thenReturn(recipient);
        when(notificationSettingService.getOrCreateSetting(recipient)).thenReturn(setting);

        notificationCreateService.createLookbookLikedNotification(new LookbookLikedEvent(
                LOOKBOOK_ID,
                RECIPIENT_ID,
                ACTOR_ID,
                "minji"
        ));

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void createAnalysisCompletedNotificationTest() {
        when(memberRepository.getReferenceById(RECIPIENT_ID)).thenReturn(recipient);
        when(notificationSettingService.getOrCreateSetting(recipient))
                .thenReturn(MemberNotificationSetting.createDefault(recipient));

        notificationCreateService.createAnalysisCompletedNotification(
                new AnalysisCompletedEvent(REPORT_ID, RECIPIENT_ID)
        );

        Notification saved = captureSavedNotification().getValue();
        assertThat(saved.getMember()).isSameAs(recipient);
        assertThat(saved.getNotificationType()).isEqualTo(NotificationType.ANALYSIS_COMPLETE);
        //시스템 알림이라 유발한 회원 없음
        assertThat(saved.getActorMemberId()).isNull();
        //분석 결과 딥링크가 끊기지 않도록 reportId만 채워야 함
        assertThat(saved.getReportId()).isEqualTo(REPORT_ID);
        assertThat(saved.getLookbookId()).isNull();
        assertThat(saved.getTrendId()).isNull();
    }

    @Test
    void createAnalysisCompletedNotificationSkipsWhenSettingDisabledTest() {
        MemberNotificationSetting setting = MemberNotificationSetting.createDefault(recipient);
        setting.changeAnalysisCompleteEnabled(false);
        when(memberRepository.getReferenceById(RECIPIENT_ID)).thenReturn(recipient);
        when(notificationSettingService.getOrCreateSetting(recipient)).thenReturn(setting);

        notificationCreateService.createAnalysisCompletedNotification(
                new AnalysisCompletedEvent(REPORT_ID, RECIPIENT_ID)
        );

        verify(notificationRepository, never()).save(any());
    }
}

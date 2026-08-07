package com.fitback.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.notification.entity.MemberNotificationSetting;
import com.fitback.backend.domain.notification.entity.Notification;
import com.fitback.backend.domain.notification.entity.NotificationType;
import com.fitback.backend.domain.notification.repository.MemberNotificationSettingRepository;
import com.fitback.backend.domain.notification.repository.NotificationRepository;
import com.fitback.backend.domain.product.repository.ProductRepository;
import com.fitback.backend.domain.recommendation.repository.RecommendedItemRepository;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot.TagInput;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RecommendationSetWriterTest {

    @Mock
    private AnalysisReportRepository analysisReportRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private RecommendedItemRepository recommendedItemRepository;

    @Mock
    private RecommendationInputSnapshotFactory snapshotFactory;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private MemberNotificationSettingRepository notificationSettingRepository;

    @Mock
    private AnalysisReport report;

    private RecommendationSetWriter writer() {
        return new RecommendationSetWriter(
                analysisReportRepository,
                productRepository,
                recommendedItemRepository,
                snapshotFactory,
                notificationRepository,
                notificationSettingRepository,
                Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void rejectsChangedInputBeforeDeletingCurrentSet() {
        RecommendationInputSnapshot input = new RecommendationInputSnapshot(
                501L,
                1L,
                1,
                List.of(new TagInput(10L, "Fixture", TagType.DETAIL))
        );
        when(analysisReportRepository.findOwnedReportForRecommendationUpdate(501L, 1L))
                .thenReturn(Optional.of(report));
        when(snapshotFactory.matches(report, input)).thenReturn(false);

        assertThatThrownBy(() -> writer().replaceCurrentSet(
                input,
                "TAG_MATCH_RATIO_V1",
                List.of()
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.RECOMMENDATION_INPUT_CHANGED);
        verify(recommendedItemRepository, never()).deleteCurrentSetByReportId(501L);
    }

    @Test
    void notifiesRequesterAfterReplacingRecommendationSet() {
        RecommendationInputSnapshot input = new RecommendationInputSnapshot(
                501L,
                1L,
                1,
                List.of(new TagInput(10L, "Fixture", TagType.DETAIL))
        );
        Member member = Member.create("member@fitback.com", "fitback", "password", LoginProvider.EMAIL);
        ReflectionTestUtils.setField(member, "id", 1L);

        when(analysisReportRepository.findOwnedReportForRecommendationUpdate(501L, 1L))
                .thenReturn(Optional.of(report));
        when(snapshotFactory.matches(report, input)).thenReturn(true);
        when(productRepository.findAllById(List.of())).thenReturn(List.of());
        when(report.getId()).thenReturn(501L);
        when(report.getMember()).thenReturn(member);
        when(notificationSettingRepository.findById(1L)).thenReturn(Optional.empty());

        writer().replaceCurrentSet(input, "TAG_MATCH_RATIO_V1", List.of());

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification notification = captor.getValue();
        assertThat(notification.getMember()).isEqualTo(member);
        assertThat(notification.getNotificationType()).isEqualTo(NotificationType.ANALYSIS_COMPLETE);
        assertThat(notification.getReportId()).isEqualTo(501L);
        assertThat(notification.getActorMemberId()).isNull();
    }

    @Test
    void skipsNotificationWhenRequesterDisabledAnalysisCompleteNotification() {
        RecommendationInputSnapshot input = new RecommendationInputSnapshot(
                501L,
                1L,
                1,
                List.of(new TagInput(10L, "Fixture", TagType.DETAIL))
        );
        Member member = Member.create("member@fitback.com", "fitback", "password", LoginProvider.EMAIL);
        ReflectionTestUtils.setField(member, "id", 1L);
        MemberNotificationSetting setting = MemberNotificationSetting.createDefault(member);
        setting.changeAnalysisCompleteEnabled(false);

        when(analysisReportRepository.findOwnedReportForRecommendationUpdate(501L, 1L))
                .thenReturn(Optional.of(report));
        when(snapshotFactory.matches(report, input)).thenReturn(true);
        when(productRepository.findAllById(List.of())).thenReturn(List.of());
        when(report.getMember()).thenReturn(member);
        when(notificationSettingRepository.findById(1L)).thenReturn(Optional.of(setting));

        writer().replaceCurrentSet(input, "TAG_MATCH_RATIO_V1", List.of());

        verify(notificationRepository, never()).save(any());
    }

}

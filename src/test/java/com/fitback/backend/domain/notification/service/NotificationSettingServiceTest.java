package com.fitback.backend.domain.notification.service;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.notification.dto.NotificationRequest;
import com.fitback.backend.domain.notification.dto.NotificationResponse;
import com.fitback.backend.domain.notification.entity.MarketingConsentHistory;
import com.fitback.backend.domain.notification.entity.MemberNotificationSetting;
import com.fitback.backend.domain.notification.repository.MarketingConsentHistoryRepository;
import com.fitback.backend.domain.notification.repository.MemberNotificationSettingRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.security.entity.AuthMember;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationSettingServiceTest {

    @Mock
    private MemberNotificationSettingRepository notificationSettingRepository;
    @Mock
    private MarketingConsentHistoryRepository marketingConsentHistoryRepository;

    @InjectMocks
    private NotificationSettingService notificationSettingService;

    //실제 db를 안 쓰므로 id 강제 세팅
    private Member createTestMember(Long id){
        Member member = Member.create("test@fitback.com", "nick", "encodedPw", LoginProvider.EMAIL);
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    //특정 on/off 상태의 설정 생성
    private MemberNotificationSetting createSetting(Member member, boolean analysis, boolean lookbook, boolean trend, boolean marketing){
        MemberNotificationSetting setting = MemberNotificationSetting.createDefault(member);
        setting.changeAnalysisCompleteEnabled(analysis);
        setting.changeLookbookLikedEnabled(lookbook);
        setting.changeTrendUpdateEnabled(trend);
        setting.changeMarketingEnabled(marketing);
        return setting;
    }

    //조회 - 설정 row가 있으면 저장된 값 그대로 반환, 생성 미발생
    @Test
    void getExistingSettingTest(){
        Member member = createTestMember(1L);
        AuthMember authMember = new AuthMember(member);
        MemberNotificationSetting setting = createSetting(member, false, true, false, true);

        when(notificationSettingRepository.findById(1L)).thenReturn(Optional.of(setting));

        NotificationResponse.NotificationSettingResponse response =
                notificationSettingService.getNotificationSettings(authMember);

        assertThat(response.analysisCompleteEnabled()).isFalse();
        assertThat(response.lookbookLikedEnabled()).isTrue();
        assertThat(response.trendUpdateEnabled()).isFalse();
        assertThat(response.marketingEnabled()).isTrue();
        //이미 존재하므로 새로 저장 안 함
        verify(notificationSettingRepository, never()).save(any());
    }

    //조회 - 설정 row가 없으면 기본값(T/T/F/F)으로 생성 후 반환
    @Test
    void getCreatesDefaultWhenMissingTest(){
        Member member = createTestMember(1L);
        AuthMember authMember = new AuthMember(member);

        when(notificationSettingRepository.findById(1L)).thenReturn(Optional.empty());
        when(notificationSettingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NotificationResponse.NotificationSettingResponse response =
                notificationSettingService.getNotificationSettings(authMember);

        assertThat(response.analysisCompleteEnabled()).isTrue();
        assertThat(response.lookbookLikedEnabled()).isTrue();
        assertThat(response.trendUpdateEnabled()).isFalse();
        assertThat(response.marketingEnabled()).isFalse();
        verify(notificationSettingRepository).save(any(MemberNotificationSetting.class));
    }

    //수정 - 모든 필드가 null이면 BAD_REQUEST, 설정 조회조차 안 함
    @Test
    void updateEmptyRequestBadRequestTest(){
        Member member = createTestMember(1L);
        AuthMember authMember = new AuthMember(member);
        NotificationRequest.UpdateNotificationSettingRequest request =
                new NotificationRequest.UpdateNotificationSettingRequest(null, null, null, null);

        assertThatThrownBy(() -> notificationSettingService.updateNotificationSettings(authMember, request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));

        //400 차단이 get-or-create보다 먼저
        verify(notificationSettingRepository, never()).findById(anyLong());
    }

    //수정 - 전달된 필드만 반영, 나머지는 기존값 유지
    @Test
    void updatePartialTest(){
        Member member = createTestMember(1L);
        AuthMember authMember = new AuthMember(member);
        MemberNotificationSetting setting = createSetting(member, true, true, false, false);
        //룩북 좋아요만 끔
        NotificationRequest.UpdateNotificationSettingRequest request =
                new NotificationRequest.UpdateNotificationSettingRequest(null, false, null, null);

        when(notificationSettingRepository.findById(1L)).thenReturn(Optional.of(setting));

        NotificationResponse.NotificationSettingResponse response =
                notificationSettingService.updateNotificationSettings(authMember, request);

        assertThat(response.lookbookLikedEnabled()).isFalse();
        //미전송 필드는 그대로
        assertThat(response.analysisCompleteEnabled()).isTrue();
        assertThat(response.trendUpdateEnabled()).isFalse();
        assertThat(response.marketingEnabled()).isFalse();
        //마케팅 미전송 -> 이력 없음
        verify(marketingConsentHistoryRepository, never()).save(any());
    }

    //수정 - 마케팅 false->true로 실제 변경 시 동의 이력(is_agreed=true) 저장
    @Test
    void updateMarketingChangedSavesHistoryTest(){
        Member member = createTestMember(1L);
        AuthMember authMember = new AuthMember(member);
        MemberNotificationSetting setting = createSetting(member, true, true, false, false);
        NotificationRequest.UpdateNotificationSettingRequest request =
                new NotificationRequest.UpdateNotificationSettingRequest(null, null, null, true);

        when(notificationSettingRepository.findById(1L)).thenReturn(Optional.of(setting));

        NotificationResponse.NotificationSettingResponse response =
                notificationSettingService.updateNotificationSettings(authMember, request);

        assertThat(response.marketingEnabled()).isTrue();

        ArgumentCaptor<MarketingConsentHistory> captor = ArgumentCaptor.forClass(MarketingConsentHistory.class);
        verify(marketingConsentHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().getIsAgreed()).isTrue();
        assertThat(captor.getValue().getMember()).isEqualTo(member);
    }

    //수정 - 마케팅 값이 기존과 같으면 이력 저장 안 함
    @Test
    void updateMarketingUnchangedNoHistoryTest(){
        Member member = createTestMember(1L);
        AuthMember authMember = new AuthMember(member);
        MemberNotificationSetting setting = createSetting(member, true, true, false, false);
        //이미 false인데 다시 false
        NotificationRequest.UpdateNotificationSettingRequest request =
                new NotificationRequest.UpdateNotificationSettingRequest(null, null, null, false);

        when(notificationSettingRepository.findById(1L)).thenReturn(Optional.of(setting));

        notificationSettingService.updateNotificationSettings(authMember, request);

        verify(marketingConsentHistoryRepository, never()).save(any());
    }

    //수정 - 마케팅 필드를 안 보내면 이력 저장 안 함
    @Test
    void updateMarketingNullNoHistoryTest(){
        Member member = createTestMember(1L);
        AuthMember authMember = new AuthMember(member);
        MemberNotificationSetting setting = createSetting(member, true, true, false, true);
        //분석 완료만 끄고 마케팅은 미전송
        NotificationRequest.UpdateNotificationSettingRequest request =
                new NotificationRequest.UpdateNotificationSettingRequest(false, null, null, null);

        when(notificationSettingRepository.findById(1L)).thenReturn(Optional.of(setting));

        notificationSettingService.updateNotificationSettings(authMember, request);

        verify(marketingConsentHistoryRepository, never()).save(any());
    }
}

package com.fitback.backend.domain.notification.service;

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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationSettingService {

    private final MemberNotificationSettingRepository notificationSettingRepository;
    private final MarketingConsentHistoryRepository marketingConsentHistoryRepository;

    //신규 회원 기본 알림 설정 생성(회원 가입 / 조회 시 알림 설정 저장값이 없을 경우 사용)
    @Transactional
    public MemberNotificationSetting createDefaultSetting(Member member) {
        return notificationSettingRepository.save(MemberNotificationSetting.createDefault(member));
    }

    //기존 회원 중 알림 설정 저장값이 없는 경우 기본 설정값 설정
    @Transactional
    public MemberNotificationSetting getOrCreateSetting(Member member) {

        return notificationSettingRepository.findById(member.getId())
                .orElseGet(() -> createDefaultSetting(member));
    }

    //알림 설정 조회 API (없으면 기본값 생성 후 반환)
    @Transactional
    public NotificationResponse.NotificationSettingResponse getNotificationSettings(AuthMember authMember) {
        MemberNotificationSetting setting = getOrCreateSetting(authMember.getMember());
        return NotificationResponse.toNotificationSettingResponse(setting);
    }

    //알림 설정 수정 API (전달된 필드만 반영, 마케팅 값이 실제 변경되면 이력 기록)
    @Transactional
    public NotificationResponse.NotificationSettingResponse updateNotificationSettings(
            AuthMember authMember,
            NotificationRequest.UpdateNotificationSettingRequest dto
    ) {
        //수정할 필드가 하나도 없으면 400
        if (dto.isEmpty()) {
            throw new BusinessException(ErrorCode.NOTIFICATION_SETTING_EMPTY);
        }

        Member member = authMember.getMember();
        MemberNotificationSetting setting = getOrCreateSetting(member);

        //마케팅 변경 여부 판단
        boolean marketingChanged =
                dto.marketingEnabled() != null
                        && !dto.marketingEnabled().equals(setting.getMarketingEnabled());

        if (dto.analysisCompleteEnabled() != null) {
            setting.changeAnalysisCompleteEnabled(dto.analysisCompleteEnabled());
        }
        if (dto.lookbookLikedEnabled() != null) {
            setting.changeLookbookLikedEnabled(dto.lookbookLikedEnabled());
        }
        if (dto.trendUpdateEnabled() != null) {
            setting.changeTrendUpdateEnabled(dto.trendUpdateEnabled());
        }
        if (dto.marketingEnabled() != null) {
            setting.changeMarketingEnabled(dto.marketingEnabled());
        }

        //마케팅 동의/철회가 실제로 바뀐 경우 이력 저장
        if (marketingChanged) {
            marketingConsentHistoryRepository.save(
                    MarketingConsentHistory.create(member, dto.marketingEnabled()));
        }

        return NotificationResponse.toNotificationSettingResponse(setting);
    }
}

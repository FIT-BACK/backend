package com.fitback.backend.domain.notification.repository;

import com.fitback.backend.domain.notification.entity.MemberNotificationSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
//회원 알림 설정 repository
public interface MemberNotificationSettingRepository extends JpaRepository<MemberNotificationSetting, Long> {
}

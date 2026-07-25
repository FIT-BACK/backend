package com.fitback.backend.domain.notification.repository;

import com.fitback.backend.domain.notification.entity.MarketingConsentHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
//마케팅 동의 이력 repository
public interface MarketingConsentHistoryRepository extends JpaRepository<MarketingConsentHistory, Long> {
}

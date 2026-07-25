package com.fitback.backend.domain.notification.entity;

import com.fitback.backend.domain.member.entity.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@Entity
@Table(name = "member_notification_setting")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberNotificationSetting {

    //member의 PK를 그대로 사용 (1:1)
    @Id
    @Column(name = "member_id")
    private Long memberId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Member member;

    //AI 분석 완료 알림 허용 여부
    @Column(name = "analysis_complete_enabled", nullable = false)
    private Boolean analysisCompleteEnabled;

    //룩북 좋아요 알림 허용 여부
    @Column(name = "lookbook_liked_enabled", nullable = false)
    private Boolean lookbookLikedEnabled;

    //트렌드 업데이트 알림 허용 여부
    @Column(name = "trend_update_enabled", nullable = false)
    private Boolean trendUpdateEnabled;

    //마케팅 알림 허용 여부
    @Column(name = "marketing_enabled", nullable = false)
    private Boolean marketingEnabled;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private MemberNotificationSetting(Member member) {
        this.member = member;
        this.analysisCompleteEnabled = true;
        this.lookbookLikedEnabled = true;
        this.trendUpdateEnabled = false;
        this.marketingEnabled = false;
    }

    //기본값 row 생성
    public static MemberNotificationSetting createDefault(Member member) {
        return new MemberNotificationSetting(member);
    }

    public void changeAnalysisCompleteEnabled(Boolean enabled) {
        this.analysisCompleteEnabled = enabled;
    }

    public void changeLookbookLikedEnabled(Boolean enabled) {
        this.lookbookLikedEnabled = enabled;
    }

    public void changeTrendUpdateEnabled(Boolean enabled) {
        this.trendUpdateEnabled = enabled;
    }

    public void changeMarketingEnabled(Boolean enabled) {
        this.marketingEnabled = enabled;
    }
}

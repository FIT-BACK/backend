package com.fitback.backend.domain.notification.entity;

import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.global.entity.BaseCreateTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Getter
@Entity
@Table(name = "marketing_consent_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketingConsentHistory extends BaseCreateTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "marketing_consent_history_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Member member;

    //true=동의, false=철회
    @Column(name = "is_agreed", nullable = false)
    private Boolean isAgreed;

    private MarketingConsentHistory(Member member, Boolean isAgreed) {
        this.member = member;
        this.isAgreed = isAgreed;
    }

    public static MarketingConsentHistory create(Member member, Boolean isAgreed) {
        return new MarketingConsentHistory(member, isAgreed);
    }
}

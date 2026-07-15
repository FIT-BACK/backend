package com.fitback.backend.domain.lookbook.entity;

import com.fitback.backend.global.entity.BaseTimeEntity;
import com.fitback.backend.domain.member.entity.Member;
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
@Table(name = "lookbook")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Lookbook extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "lookbook_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Member member;

    @Column(name = "original_image_url", nullable = false, length = 2048)
    private String originalImageUrl;

    @Column(name = "matched_image_url", nullable = false, length = 2048)
    private String matchedImageUrl;

    @Column(name = "purchase_url", length = 2048)
    private String purchaseUrl;


    private Lookbook(Member member, String originalImageUrl, String matchedImageUrl, String purchaseUrl) {
        this.member = member;
        this.originalImageUrl = originalImageUrl;
        this.matchedImageUrl = matchedImageUrl;
        this.purchaseUrl = purchaseUrl;
    }

    public static Lookbook create(Member member, String originalImageUrl, String matchedImageUrl, String purchaseUrl) {
        return new Lookbook(member, originalImageUrl, matchedImageUrl, purchaseUrl);
    }

    public void changePurchaseUrl(String purchaseUrl) {
        this.purchaseUrl = purchaseUrl;
    }
}

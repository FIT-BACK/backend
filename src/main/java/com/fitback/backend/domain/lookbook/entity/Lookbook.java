package com.fitback.backend.domain.lookbook.entity;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "original_image_id", nullable = false)
    private Image originalImage;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "matched_image_id", nullable = false)
    private Image matchedImage;

    @Column(name = "purchase_url", length = 2048)
    private String purchaseUrl;

    @Column(name = "comment", length = 500)
    private String comment;

    @Column(name = "like_count", nullable = false)
    private Integer likeCount = 0;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "report_count", nullable = false)
    private Integer reportCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", nullable = false, length = 20)
    private LookbookModerationStatus moderationStatus = LookbookModerationStatus.VISIBLE;

    @Column(name = "auto_hidden_at")
    private LocalDateTime autoHiddenAt;

    private Lookbook(
            Member member,
            Image originalImage,
            Image matchedImage,
            String purchaseUrl,
            String comment
    ) {
        this.member = member;
        this.originalImage = originalImage;
        this.matchedImage = matchedImage;
        this.purchaseUrl = purchaseUrl;
        this.comment = comment;
    }

    public static Lookbook create(
            Member member,
            Image originalImage,
            Image matchedImage,
            String purchaseUrl,
            String comment
    ) {
        return new Lookbook(member, originalImage, matchedImage, purchaseUrl, comment);
    }

    public void update(
            Image originalImage,
            Image matchedImage,
            String purchaseUrl,
            String comment
    ) {
        this.originalImage = originalImage;
        this.matchedImage = matchedImage;
        this.purchaseUrl = purchaseUrl;
        this.comment = comment;
    }

    public void changePurchaseUrl(String purchaseUrl) {
        this.purchaseUrl = purchaseUrl;
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}

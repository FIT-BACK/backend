package com.fitback.backend.domain.lookbook.entity;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.product.entity.Product;
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

    //회원 탈퇴 시 삭제하지 않고 '탈퇴한 유저'로 익명화(재지정)하므로 cascade 미적용
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "original_image_id", nullable = false)
    private Image originalImage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "matched_image_id")
    private Image matchedImage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "matched_product_id")
    private Product matchedProduct;

    @Column(name = "matched_product_image_url", length = 2048)
    private String matchedProductImageUrl;

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
            Product matchedProduct,
            String purchaseUrl,
            String comment
    ) {
        this.member = member;
        this.originalImage = originalImage;
        this.matchedImage = matchedImage;
        this.matchedProduct = matchedProduct;
        this.matchedProductImageUrl = matchedProduct == null
                ? null
                : matchedProduct.getImageUrl();
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
        return new Lookbook(
                member,
                originalImage,
                matchedImage,
                null,
                purchaseUrl,
                comment
        );
    }

    public static Lookbook createWithProduct(
            Member member,
            Image originalImage,
            Product matchedProduct,
            String purchaseUrl,
            String comment
    ) {
        return new Lookbook(
                member,
                originalImage,
                null,
                matchedProduct,
                purchaseUrl,
                comment
        );
    }

    public void update(
            Image originalImage,
            Image matchedImage,
            String purchaseUrl,
            String comment
    ) {
        this.originalImage = originalImage;
        this.matchedImage = matchedImage;
        this.matchedProduct = null;
        this.matchedProductImageUrl = null;
        this.purchaseUrl = purchaseUrl;
        this.comment = comment;
    }

    public void updateWithProduct(
            Image originalImage,
            Product matchedProduct,
            String purchaseUrl,
            String comment
    ) {
        this.originalImage = originalImage;
        this.matchedImage = null;
        this.matchedProduct = matchedProduct;
        this.matchedProductImageUrl = matchedProduct == null
                ? null
                : matchedProduct.getImageUrl();
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

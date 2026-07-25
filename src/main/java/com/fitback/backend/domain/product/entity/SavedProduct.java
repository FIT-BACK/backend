package com.fitback.backend.domain.product.entity;

import com.fitback.backend.domain.member.entity.Member;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "saved_product")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedProduct {

    @EmbeddedId
    private SavedProductId id;

    @MapsId("memberId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @MapsId("productId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private SavedProduct(Member member, Product product) {
        this.member = Objects.requireNonNull(member, "member must not be null");
        this.product = Objects.requireNonNull(product, "product must not be null");
        this.id = SavedProductId.create(member.getId(), product.getId());
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public static SavedProduct create(Member member, Product product) {
        return new SavedProduct(member, product);
    }
}

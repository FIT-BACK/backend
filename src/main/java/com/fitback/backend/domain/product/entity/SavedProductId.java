package com.fitback.backend.domain.product.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedProductId implements Serializable {

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "product_id")
    private Long productId;

    private SavedProductId(Long memberId, Long productId) {
        this.memberId = Objects.requireNonNull(memberId, "memberId must not be null");
        this.productId = Objects.requireNonNull(productId, "productId must not be null");
    }

    public static SavedProductId create(Long memberId, Long productId) {
        return new SavedProductId(memberId, productId);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof SavedProductId that)) {
            return false;
        }
        return Objects.equals(memberId, that.memberId)
                && Objects.equals(productId, that.productId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(memberId, productId);
    }
}

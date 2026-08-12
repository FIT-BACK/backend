package com.fitback.backend.domain.closet.entity;

import com.fitback.backend.domain.product.dto.ProductDetailResponse;
import com.fitback.backend.domain.product.dto.ProductPriceResponse;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.recommendation.entity.RecommendedItem;
import com.fitback.backend.global.entity.BaseCreateTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Getter
@Entity
@Table(
        name = "saved_analysis_item",
        uniqueConstraints = @UniqueConstraint(
                name = "UK_SAVED_ANALYSIS_ITEM_SAVE_CATEGORY",
                columnNames = {"save_id", "category"}
        ),
        indexes = @Index(
                name = "IDX_SAVED_ANALYSIS_ITEM_PRODUCT",
                columnList = "product_id"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedAnalysisItem extends BaseCreateTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "saved_analysis_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "save_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ClosetSave closetSave;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private ProductCategory category;

    @Column(name = "rank_no", nullable = false)
    private Integer rankNo;

    @Column(name = "image_url", length = 2048)
    private String imageUrl;

    @Column(name = "product_name", length = 255)
    private String name;

    @Column(name = "seller_name", length = 255)
    private String sellerName;

    @Column(name = "price_amount", precision = 19, scale = 2)
    private BigDecimal priceAmount;

    @Column(name = "price_currency", length = 3, columnDefinition = "CHAR(3)")
    private String priceCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_type", length = 20)
    private SavedAnalysisPriceType priceType;

    @Column(name = "price_observed_at")
    private Instant priceObservedAt;

    @Column(name = "purchase_url", length = 2048)
    private String purchaseUrl;

    @Column(name = "similarity_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal similarityScore;

    @Column(name = "final_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal finalScore;

    private SavedAnalysisItem(
            ClosetSave closetSave,
            RecommendedItem recommendedItem,
            ProductDetailResponse liveDetail
    ) {
        Product selectedProduct = recommendedItem.getProduct();
        this.closetSave = Objects.requireNonNull(closetSave, "closetSave must not be null");
        this.product = Objects.requireNonNull(selectedProduct, "product must not be null");
        this.category = recommendedItem.getCategory();
        this.rankNo = recommendedItem.getRankNo();
        // IDENTITY_ONLY 저장 모드 상품은 Product 엔티티 컬럼(imageUrl/name/sellerName/
        // purchaseUrl/가격)에 표시 데이터를 아예 저장하지 않고 응답 시점에만 live로
        // hydrate한다(RecommendationQueryService.toResponse와 동일한 전제). 그걸 모르고
        // selectedProduct.getXxx()를 그대로 스냅샷하면 이 카테고리 상품은 저장 즉시
        // "상품 정보 없음"으로 영구히 굳어버리고, 이미지가 없다는 이유로 룩북 게시도
        // 거절당함 — liveDetail이 있으면 그걸 우선 스냅샷한다.
        if (liveDetail != null) {
            this.imageUrl = liveDetail.imageUrl();
            this.name = liveDetail.name();
            this.sellerName = liveDetail.sellerName();
            snapshotPrice(liveDetail.price());
            this.purchaseUrl = liveDetail.purchaseUrl();
        } else {
            this.imageUrl = selectedProduct.getImageUrl();
            this.name = selectedProduct.getName();
            this.sellerName = selectedProduct.getSellerName();
            snapshotPrice(selectedProduct);
            this.purchaseUrl = selectedProduct.getPurchaseUrl();
        }
        this.similarityScore = recommendedItem.getSimilarityScore();
        this.finalScore = recommendedItem.getFinalScore();
    }

    public static SavedAnalysisItem from(
            ClosetSave closetSave,
            RecommendedItem recommendedItem
    ) {
        return new SavedAnalysisItem(closetSave, recommendedItem, null);
    }

    // 저장 시점에 IDENTITY_ONLY 상품의 표시 데이터를 live hydrate해서 넘겨줄 때 사용.
    public static SavedAnalysisItem from(
            ClosetSave closetSave,
            RecommendedItem recommendedItem,
            ProductDetailResponse liveDetail
    ) {
        return new SavedAnalysisItem(closetSave, recommendedItem, liveDetail);
    }

    private void snapshotPrice(Product selectedProduct) {
        if (selectedProduct.getSalePrice() != null) {
            priceAmount = selectedProduct.getSalePrice();
            priceType = SavedAnalysisPriceType.SALE;
        } else if (selectedProduct.getCurrentPrice() != null) {
            priceAmount = selectedProduct.getCurrentPrice();
            priceType = SavedAnalysisPriceType.CURRENT;
        } else if (selectedProduct.getListPrice() != null) {
            priceAmount = selectedProduct.getListPrice();
            priceType = SavedAnalysisPriceType.LIST;
        }
        if (priceAmount != null) {
            priceCurrency = selectedProduct.getCurrency();
            priceObservedAt = selectedProduct.getPriceObservedAt();
        }
    }

    private void snapshotPrice(ProductPriceResponse price) {
        if (price == null) {
            return;
        }
        priceAmount = price.amount();
        priceType = SavedAnalysisPriceType.valueOf(price.type().name());
        priceCurrency = price.currency();
        priceObservedAt = price.observedAt();
    }
}

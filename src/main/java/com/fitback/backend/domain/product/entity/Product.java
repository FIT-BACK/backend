package com.fitback.backend.domain.product.entity;

import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProductStorageMode;
import com.fitback.backend.domain.product.service.model.ProviderIdentityType;
import com.fitback.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "product",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "UK_PRODUCT_SOURCE_IDENTITY",
                    columnNames = {"source_api", "provider_identity_key"}
            ),
            @UniqueConstraint(
                    name = "UK_PRODUCT_MATERIALIZATION_KEY",
                    columnNames = "materialization_key"
            )
        },
        indexes = @Index(
                name = "IDX_PRODUCT_CATEGORY_AVAILABILITY",
                columnList = "category, availability"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id")
    private Long id;

    @Column(name = "source_api", nullable = false, length = 50)
    private String sourceApi;

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_strategy", nullable = false, length = 30)
    private ProviderIdentityType identityStrategy;

    @Column(name = "provider_identity_key", length = 64, columnDefinition = "CHAR(64)")
    private String providerIdentityKey;

    @Column(name = "materialization_key", length = 64, columnDefinition = "CHAR(64)")
    private String materializationKey;

    @Column(name = "external_product_id", length = 255)
    private String externalProductId;

    @Column(name = "external_variant_id", length = 255)
    private String externalVariantId;

    @Column(name = "merchant_id", length = 255)
    private String merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_mode", nullable = false, length = 20)
    private ProductStorageMode storageMode;

    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "brand_name", length = 255)
    private String brandName;

    @Column(name = "seller_name", length = 255)
    private String sellerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 30)
    private ProductCategory category;

    @Column(name = "image_url", length = 2048)
    private String imageUrl;

    @Column(name = "list_price", precision = 19, scale = 2)
    private BigDecimal listPrice;

    @Column(name = "current_price", precision = 19, scale = 2)
    private BigDecimal currentPrice;

    @Column(name = "sale_price", precision = 19, scale = 2)
    private BigDecimal salePrice;

    @Column(name = "currency", length = 3, columnDefinition = "CHAR(3)")
    private String currency;

    @Column(name = "price_observed_at")
    private Instant priceObservedAt;

    @Column(name = "purchase_url", length = 2048)
    private String purchaseUrl;

    @Column(name = "affiliate_url", length = 2048)
    private String affiliateUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "availability", nullable = false, length = 30)
    private ProductAvailability availability;

    @Column(name = "snapshot_expires_at")
    private Instant snapshotExpiresAt;

    @Builder(access = AccessLevel.PRIVATE)
    private Product(
            String sourceApi,
            ProviderIdentityType identityStrategy,
            String providerIdentityKey,
            String materializationKey,
            String externalProductId,
            String externalVariantId,
            String merchantId,
            ProductStorageMode storageMode,
            String name,
            String brandName,
            String sellerName,
            ProductCategory category,
            String imageUrl,
            BigDecimal listPrice,
            BigDecimal currentPrice,
            BigDecimal salePrice,
            String currency,
            Instant priceObservedAt,
            String purchaseUrl,
            String affiliateUrl,
            ProductAvailability availability,
            Instant snapshotExpiresAt
    ) {
        this.sourceApi = requireText(sourceApi, "sourceApi");
        this.identityStrategy = Objects.requireNonNull(
                identityStrategy,
                "identityStrategy must not be null"
        );
        this.providerIdentityKey = providerIdentityKey;
        this.materializationKey = materializationKey;
        this.externalProductId = externalProductId;
        this.externalVariantId = externalVariantId;
        this.merchantId = merchantId;
        this.storageMode = Objects.requireNonNull(storageMode, "storageMode must not be null");
        validateIdentity();
        refreshSnapshot(
                name,
                brandName,
                sellerName,
                category,
                imageUrl,
                listPrice,
                currentPrice,
                salePrice,
                currency,
                priceObservedAt,
                purchaseUrl,
                affiliateUrl,
                availability,
                snapshotExpiresAt
        );
    }

    public static Product createProviderProduct(
            String sourceApi,
            ProviderIdentityType identityStrategy,
            String providerIdentityKey,
            String materializationKey,
            String externalProductId,
            String externalVariantId,
            String merchantId,
            ProductStorageMode storageMode,
            String name,
            String brandName,
            String sellerName,
            ProductCategory category,
            String imageUrl,
            BigDecimal listPrice,
            BigDecimal currentPrice,
            BigDecimal salePrice,
            String currency,
            Instant priceObservedAt,
            String purchaseUrl,
            String affiliateUrl,
            ProductAvailability availability,
            Instant snapshotExpiresAt
    ) {
        return Product.builder()
                .sourceApi(sourceApi)
                .identityStrategy(identityStrategy)
                .providerIdentityKey(providerIdentityKey)
                .materializationKey(materializationKey)
                .externalProductId(externalProductId)
                .externalVariantId(externalVariantId)
                .merchantId(merchantId)
                .storageMode(storageMode)
                .name(name)
                .brandName(brandName)
                .sellerName(sellerName)
                .category(category)
                .imageUrl(imageUrl)
                .listPrice(listPrice)
                .currentPrice(currentPrice)
                .salePrice(salePrice)
                .currency(currency)
                .priceObservedAt(priceObservedAt)
                .purchaseUrl(purchaseUrl)
                .affiliateUrl(affiliateUrl)
                .availability(availability)
                .snapshotExpiresAt(snapshotExpiresAt)
                .build();
    }

    public void refreshSnapshot(
            String name,
            String brandName,
            String sellerName,
            ProductCategory category,
            String imageUrl,
            BigDecimal listPrice,
            BigDecimal currentPrice,
            BigDecimal salePrice,
            String currency,
            Instant priceObservedAt,
            String purchaseUrl,
            String affiliateUrl,
            ProductAvailability availability,
            Instant snapshotExpiresAt
    ) {
        validatePriceMetadata(listPrice, currentPrice, salePrice, currency, priceObservedAt);
        this.name = name;
        this.brandName = brandName;
        this.sellerName = sellerName;
        this.category = category;
        this.imageUrl = imageUrl;
        this.listPrice = listPrice;
        this.currentPrice = currentPrice;
        this.salePrice = salePrice;
        this.currency = currency;
        this.priceObservedAt = priceObservedAt;
        this.purchaseUrl = purchaseUrl;
        this.affiliateUrl = affiliateUrl;
        this.availability = Objects.requireNonNull(
                availability,
                "availability must not be null"
        );
        this.snapshotExpiresAt = snapshotExpiresAt;
    }

    public void markUnavailable() {
        this.availability = ProductAvailability.UNAVAILABLE;
    }

    public boolean hasDisplayData() {
        return name != null && !name.isBlank();
    }

    private void validateIdentity() {
        if (identityStrategy == ProviderIdentityType.PROVIDER_KEY
                && (providerIdentityKey == null || externalProductId == null)) {
            throw new IllegalArgumentException(
                    "provider identity requires identity key and external product id"
            );
        }
        if (identityStrategy == ProviderIdentityType.SNAPSHOT_UUID
                && materializationKey == null) {
            throw new IllegalArgumentException("snapshot identity requires materialization key");
        }
    }

    private static void validatePriceMetadata(
            BigDecimal listPrice,
            BigDecimal currentPrice,
            BigDecimal salePrice,
            String currency,
            Instant observedAt
    ) {
        boolean hasPrice = listPrice != null || currentPrice != null || salePrice != null;
        if (hasPrice != (currency != null && observedAt != null)) {
            throw new IllegalArgumentException(
                    "price values require currency and observedAt, and vice versa"
            );
        }
        requireNonNegative(listPrice, "listPrice");
        requireNonNegative(currentPrice, "currentPrice");
        requireNonNegative(salePrice, "salePrice");
    }

    private static void requireNonNegative(BigDecimal value, String fieldName) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}

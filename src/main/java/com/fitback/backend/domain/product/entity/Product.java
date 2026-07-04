package com.fitback.backend.domain.product.entity;

import com.fitback.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "product")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id")
    private Long id;

    @Column(name = "external_product_id", length = 100)
    private String externalProductId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "brand_name", length = 100)
    private String brandName;

    @Column(name = "seller_name", nullable = false, length = 100)
    private String sellerName;

    @Column(name = "price", nullable = false)
    private Integer price;

    @Column(name = "average_price")
    private Integer averagePrice;

    @Column(name = "category", nullable = false, length = 50)
    private String category;

    @Column(name = "season", length = 20)
    private String season;

    @Column(name = "gender", length = 10)
    private String gender;

    @Column(name = "purchase_url", nullable = false, length = 2048)
    private String purchaseUrl;

    @Column(name = "image_url", nullable = false, length = 2048)
    private String imageUrl;

    @Column(name = "source_api", nullable = false, length = 50)
    private String sourceApi;


    @Builder(access = AccessLevel.PRIVATE)
    private Product(
            String externalProductId,
            String name,
            String brandName,
            String sellerName,
            Integer price,
            Integer averagePrice,
            String category,
            String season,
            String gender,
            String purchaseUrl,
            String imageUrl,
            String sourceApi
    ) {
        this.externalProductId = externalProductId;
        this.name = name;
        this.brandName = brandName;
        this.sellerName = sellerName;
        this.price = price;
        this.averagePrice = averagePrice;
        this.category = category;
        this.season = season;
        this.gender = gender;
        this.purchaseUrl = purchaseUrl;
        this.imageUrl = imageUrl;
        this.sourceApi = sourceApi;
    }

    public static Product create(
            String externalProductId,
            String name,
            String brandName,
            String sellerName,
            Integer price,
            Integer averagePrice,
            String category,
            String season,
            String gender,
            String purchaseUrl,
            String imageUrl,
            String sourceApi
    ) {
        return Product.builder()
                .externalProductId(externalProductId)
                .name(name)
                .brandName(brandName)
                .sellerName(sellerName)
                .price(price)
                .averagePrice(averagePrice)
                .category(category)
                .season(season)
                .gender(gender)
                .purchaseUrl(purchaseUrl)
                .imageUrl(imageUrl)
                .sourceApi(sourceApi)
                .build();
    }

    public void changePrice(Integer price, Integer averagePrice) {
        this.price = price;
        this.averagePrice = averagePrice;
    }
}

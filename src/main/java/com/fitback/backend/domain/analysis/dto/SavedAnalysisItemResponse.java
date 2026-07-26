package com.fitback.backend.domain.analysis.dto;

import com.fitback.backend.domain.closet.entity.SavedAnalysisItem;
import com.fitback.backend.domain.product.dto.ProductPriceResponse;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import java.math.BigDecimal;

public record SavedAnalysisItemResponse(
        ProductCategory category,
        Long productId,
        Integer rank,
        String imageUrl,
        String name,
        String sellerName,
        ProductPriceResponse price,
        String purchaseUrl,
        BigDecimal similarityScore,
        BigDecimal finalScore
) {

    public static SavedAnalysisItemResponse from(SavedAnalysisItem item) {
        ProductPriceResponse price = item.getPriceAmount() == null
                ? null
                : new ProductPriceResponse(
                        item.getPriceAmount(),
                        item.getPriceCurrency(),
                        item.getPriceType().toResponseType(),
                        item.getPriceObservedAt()
                );
        return new SavedAnalysisItemResponse(
                item.getCategory(),
                item.getProduct().getId(),
                item.getRankNo(),
                item.getImageUrl(),
                item.getName(),
                item.getSellerName(),
                price,
                item.getPurchaseUrl(),
                item.getSimilarityScore(),
                item.getFinalScore()
        );
    }
}

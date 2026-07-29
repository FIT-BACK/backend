package com.fitback.backend.external.shopping.shopify;

import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.port.ProductCategoryMapper;
import java.util.Locale;
import java.util.Optional;

public final class ShopifyProductCategoryMapper implements ProductCategoryMapper {

    @Override
    public Optional<ProductCategory> map(String provider, String categoryPath) {
        if (!ShopifyGlobalCatalogAdapter.PROVIDER.equals(provider)
                || categoryPath == null
                || categoryPath.isBlank()) {
            return Optional.empty();
        }

        String normalized = categoryPath.trim().toLowerCase(Locale.ROOT);
        for (ProductCategory category : ProductCategory.values()) {
            if (category.name().equalsIgnoreCase(normalized)) {
                return Optional.of(category);
            }
        }
        if (containsAny(normalized, "dress", "gown", "jumpsuit")) {
            return Optional.of(ProductCategory.DRESS);
        }
        if (containsAny(normalized, "jacket", "coat", "cardigan", "blazer", "outer")) {
            return Optional.of(ProductCategory.OUTER);
        }
        if (containsAny(normalized, "pants", "trouser", "jean", "skirt", "shorts", "bottom")) {
            return Optional.of(ProductCategory.BOTTOM);
        }
        if (containsAny(normalized, "shoe", "sneaker", "boot", "sandal", "heel", "loafer")) {
            return Optional.of(ProductCategory.SHOES);
        }
        if (containsAny(normalized, "bag", "backpack", "tote", "purse")) {
            return Optional.of(ProductCategory.BAG);
        }
        if (containsAny(normalized, "shirt", "tee", "blouse", "sweater", "sweatshirt",
                "hoodie", "top")) {
            return Optional.of(ProductCategory.TOP);
        }
        if (containsAny(normalized, "hat", "cap", "belt", "scarf", "jewelry", "sock",
                "accessory")) {
            return Optional.of(ProductCategory.ACCESSORY);
        }
        return Optional.of(ProductCategory.OTHER);
    }

    String searchTerm(ProductCategory category) {
        return switch (category) {
            case OUTER -> "jacket coat";
            case TOP -> "shirt top";
            case BOTTOM -> "pants bottom";
            case DRESS -> "dress";
            case SHOES -> "shoes";
            case BAG -> "bag";
            case ACCESSORY -> "fashion accessory";
            case OTHER -> "";
        };
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}

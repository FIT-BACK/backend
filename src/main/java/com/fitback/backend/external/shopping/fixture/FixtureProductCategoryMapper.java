package com.fitback.backend.external.shopping.fixture;

import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.port.ProductCategoryMapper;
import java.util.Locale;
import java.util.Optional;

public class FixtureProductCategoryMapper implements ProductCategoryMapper {

    private static final String PROVIDER = "fixture";

    @Override
    public Optional<ProductCategory> map(String provider, String categoryPath) {
        if (!PROVIDER.equals(provider) || categoryPath == null || categoryPath.isBlank()) {
            return Optional.empty();
        }

        String normalized = categoryPath.trim().toLowerCase(Locale.ROOT);
        return Optional.of(switch (normalized) {
            case "outer", "outer/jacket" -> ProductCategory.OUTER;
            case "top", "tops/shirts" -> ProductCategory.TOP;
            case "bottom", "bottoms/pants" -> ProductCategory.BOTTOM;
            case "dress", "dresses" -> ProductCategory.DRESS;
            case "shoes" -> ProductCategory.SHOES;
            case "bag", "bags" -> ProductCategory.BAG;
            case "accessory", "accessories" -> ProductCategory.ACCESSORY;
            default -> ProductCategory.OTHER;
        });
    }
}

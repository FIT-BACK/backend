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
        if (containsAnyToken(normalized,
                "dress", "dresses", "gown", "gowns", "jumpsuit", "jumpsuits")
                || containsAny(normalized, "원피스", "드레스", "점프수트")) {
            return Optional.of(ProductCategory.DRESS);
        }
        if (containsAnyToken(normalized,
                "jacket", "jackets", "coat", "coats", "cardigan", "cardigans",
                "blazer", "blazers", "outer", "outerwear")
                || containsAny(normalized,
                "자켓", "재킷", "코트", "가디건", "블레이저", "아우터")) {
            return Optional.of(ProductCategory.OUTER);
        }
        if (containsAnyToken(normalized,
                "pant", "pants", "trouser", "trousers", "jean", "jeans", "denim",
                "skirt", "skirts", "short", "shorts", "bottom", "bottoms", "진")
                || containsAny(normalized,
                "팬츠", "바지", "청바지", "데님", "스커트", "치마", "반바지", "하의")) {
            return Optional.of(ProductCategory.BOTTOM);
        }
        if (containsAnyToken(normalized,
                "shoe", "shoes", "sneaker", "sneakers", "boot", "boots", "sandal",
                "sandals", "heel", "heels", "loafer", "loafers")
                || containsAny(normalized, "신발", "스니커즈", "부츠", "샌들", "구두", "로퍼")) {
            return Optional.of(ProductCategory.SHOES);
        }
        if (containsAnyToken(normalized,
                "bag", "bags", "handbag", "handbags", "backpack", "backpacks",
                "tote", "totes", "purse", "purses", "crossbody")
                || containsAny(normalized, "가방", "백팩", "토트백", "파우치", "크로스백")) {
            return Optional.of(ProductCategory.BAG);
        }
        if (containsAnyToken(normalized,
                "shirt", "shirts", "tshirt", "tshirts", "tee", "tees", "blouse",
                "blouses", "sweater", "sweaters", "sweatshirt", "sweatshirts",
                "hoodie", "hoodies", "top", "tops", "tanktop", "tanktops")
                || containsAny(normalized,
                "셔츠", "티셔츠", "블라우스", "스웨터", "니트", "후드", "상의")) {
            return Optional.of(ProductCategory.TOP);
        }
        if (containsAnyToken(normalized,
                "hat", "hats", "cap", "caps", "belt", "belts", "scarf", "scarves",
                "jewelry", "jewellery", "sock", "socks", "accessory", "accessories")
                || containsAny(normalized,
                "모자", "벨트", "스카프", "목걸이", "귀걸이", "양말", "액세서리")) {
            return Optional.of(ProductCategory.ACCESSORY);
        }
        // 키워드가 전혀 안 맞으면 OTHER로 단정하지 않고 empty를 반환한다 —
        // 호출부(ProductCandidateMapper)가 상품명을 2차 신호로 재시도할 수 있게 하기 위함.
        return Optional.empty();
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

    private static boolean containsAnyToken(String value, String... candidates) {
        for (String token : value.split("[^\\p{L}\\p{N}]+")) {
            for (String candidate : candidates) {
                if (token.equals(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }
}

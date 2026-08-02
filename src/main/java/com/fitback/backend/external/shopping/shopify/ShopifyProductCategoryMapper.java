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
        if (containsAny(normalized, "dress", "gown", "jumpsuit", "원피스", "드레스", "점프수트")) {
            return Optional.of(ProductCategory.DRESS);
        }
        if (containsAny(normalized, "jacket", "coat", "cardigan", "blazer", "outer",
                "자켓", "재킷", "코트", "가디건", "블레이저", "아우터")) {
            return Optional.of(ProductCategory.OUTER);
        }
        // "진"은 "청바지"의 구어적 줄임말로 패션 상품명에 흔히 쓰여 포함시킴 —
        // "진주"/"진짜" 같은 무관한 단어를 오분류할 여지가 있지만, 카테고리 탭 배치
        // 정도의 낮은 리스크라 상품명 기반 2차 매칭에서는 감수할만한 트레이드오프.
        if (containsAny(normalized, "pants", "trouser", "jean", "denim", "skirt", "shorts", "bottom",
                "팬츠", "바지", "청바지", "데님", "스커트", "치마", "반바지", "하의", "진")) {
            return Optional.of(ProductCategory.BOTTOM);
        }
        if (containsAny(normalized, "shoe", "sneaker", "boot", "sandal", "heel", "loafer",
                "신발", "스니커즈", "부츠", "샌들", "구두", "로퍼")) {
            return Optional.of(ProductCategory.SHOES);
        }
        if (containsAny(normalized, "bag", "backpack", "tote", "purse",
                "가방", "백팩", "토트백", "파우치", "크로스백")) {
            return Optional.of(ProductCategory.BAG);
        }
        if (containsAny(normalized, "shirt", "tee", "blouse", "sweater", "sweatshirt",
                "hoodie", "top", "셔츠", "티셔츠", "블라우스", "스웨터", "니트", "후드", "상의")) {
            return Optional.of(ProductCategory.TOP);
        }
        if (containsAny(normalized, "hat", "cap", "belt", "scarf", "jewelry", "sock",
                "accessory", "모자", "벨트", "스카프", "목걸이", "귀걸이", "양말", "액세서리")) {
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
}

package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot.TagInput;
import com.fitback.backend.domain.tag.entity.TagType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class RecommendationRetrievalQueryPlanner {

    static final int MAX_QUERY_COUNT = 5;
    private static final int MAX_SEMANTIC_QUERY_COUNT = MAX_QUERY_COUNT - 1;

    private static final Map<String, String> SILHOUETTE_ALIASES = Map.ofEntries(
            Map.entry("와이드핏", "wide-leg"),
            Map.entry("슬림핏", "slim-fit"),
            Map.entry("오버사이즈", "oversized"),
            Map.entry("레귤러핏", "regular-fit"),
            Map.entry("A라인", "a-line"),
            Map.entry("크롭", "cropped"),
            Map.entry("로우라이즈", "low-rise"),
            Map.entry("하이라이즈", "high-rise"),
            Map.entry("미디기장", "midi"),
            Map.entry("롱기장", "maxi")
    );
    private static final Map<String, String> DETAIL_ALIASES = Map.ofEntries(
            Map.entry("브이넥", "v-neck"),
            Map.entry("터틀넥", "turtleneck"),
            Map.entry("라운드넥", "crewneck"),
            Map.entry("러플/프릴", "ruffle"),
            Map.entry("지퍼", "zip"),
            Map.entry("벨트", "belted"),
            Map.entry("포켓", "pocket"),
            Map.entry("슬릿", "slit"),
            Map.entry("단추", "button")
    );
    private static final Map<String, String> MATERIAL_ALIASES = Map.ofEntries(
            Map.entry("데님", "denim"),
            Map.entry("니트", "knit"),
            Map.entry("코튼", "cotton"),
            Map.entry("린넨", "linen"),
            Map.entry("가죽", "leather"),
            Map.entry("트위드", "tweed"),
            Map.entry("시폰", "chiffon")
    );
    private static final Map<String, String> COLOR_ALIASES = Map.ofEntries(
            Map.entry("화이트", "white"),
            Map.entry("블랙", "black"),
            Map.entry("베이지", "beige"),
            Map.entry("네이비", "navy"),
            Map.entry("그레이", "gray"),
            Map.entry("브라운", "brown"),
            Map.entry("카키", "khaki")
    );

    /**
     * 태그 일치도 채점(RecommendationScorer)이 검색어 구성과 동일한 영어 별칭
     * 기준으로 비교하도록 공개하는 단일 태그 조회용 메서드. 큐레이션된 별칭이
     * 없는 태그(STYLE, 또는 별칭 표에 없는 태그)는 검색에서도 원문 그대로
     * 쓰지 않는 것과 동일하게 null을 반환한다 — 호출부가 "검증 불가"로 다룬다.
     */
    public String aliasFor(TagInput tag) {
        Map<String, String> aliases = aliasesFor(tag.tagType());
        return aliases == null ? null : aliases.get(tag.name());
    }

    private static Map<String, String> aliasesFor(TagType type) {
        return switch (type) {
            case SILHOUETTE -> SILHOUETTE_ALIASES;
            case DETAIL -> DETAIL_ALIASES;
            case MATERIAL -> MATERIAL_ALIASES;
            case COLOR -> COLOR_ALIASES;
            default -> null;
        };
    }

    public List<PlannedQuery> plan(ProductCategory category, List<TagInput> tags) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(tags, "tags must not be null");

        String silhouette = firstAlias(tags, TagType.SILHOUETTE, SILHOUETTE_ALIASES);
        String detail = firstAlias(tags, TagType.DETAIL, DETAIL_ALIASES);
        String material = firstAlias(tags, TagType.MATERIAL, MATERIAL_ALIASES);
        String color = firstAlias(tags, TagType.COLOR, COLOR_ALIASES);

        List<Signal> signals = new ArrayList<>(3);
        if (silhouette != null) {
            signals.add(new Signal(silhouette, "SILHOUETTE"));
        }
        if (detail != null) {
            signals.add(new Signal(detail, "DETAIL"));
        }
        if (material != null) {
            signals.add(new Signal(material, "MATERIAL"));
        }

        LinkedHashMap<String, PlannedQuery> semanticQueries = new LinkedHashMap<>();
        if (signals.isEmpty()) {
            // 실루엣/디테일/소재가 하나도 없으면(색상만 태그된 경우) 색상이라도
            // 단독 검색어로 살린다 — 이전에는 색상이 항상 다른 속성의 보정용으로만
            // 쓰여서, 색상만 태그했을 땐 검색어가 하나도 안 만들어졌었다.
            if (color != null) {
                semanticQueries.put(color, new PlannedQuery(color, "COLOR"));
            }
        } else {
            // 태그된 속성 타입마다 단독 검색어를 먼저 예산에 확정한다 — "+색상" 조합을
            // 앞 타입부터 채우다 예산(MAX_SEMANTIC_QUERY_COUNT)이 차서 뒤 타입(예: 소재)의
            // 검색어 자체가 통째로 밀려나던 문제를 막기 위함. 조합은 남는 예산에만 채운다.
            for (Signal signal : signals) {
                semanticQueries.putIfAbsent(
                        signal.keyword(),
                        new PlannedQuery(signal.keyword(), signal.composition())
                );
            }
            if (color != null) {
                for (Signal signal : signals) {
                    String refined = signal.keyword() + " " + color;
                    semanticQueries.putIfAbsent(
                            refined,
                            new PlannedQuery(refined, signal.composition() + "+COLOR")
                    );
                }
            }
        }

        List<PlannedQuery> plan = new ArrayList<>(MAX_QUERY_COUNT);
        semanticQueries.values().stream()
                .limit(MAX_SEMANTIC_QUERY_COUNT)
                .forEach(plan::add);
        plan.add(new PlannedQuery("", "CATEGORY"));
        return List.copyOf(plan);
    }

    private static String firstAlias(
            List<TagInput> tags,
            TagType type,
            Map<String, String> aliases
    ) {
        return tags.stream()
                .filter(Objects::nonNull)
                .filter(tag -> tag.tagType() == type)
                .map(TagInput::name)
                .filter(Objects::nonNull)
                .map(aliases::get)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private record Signal(String keyword, String composition) {
    }

    public record PlannedQuery(String keyword, String tagTypeComposition) {

        public PlannedQuery {
            keyword = Objects.requireNonNull(keyword, "keyword must not be null").trim();
            tagTypeComposition = Objects.requireNonNull(
                    tagTypeComposition,
                    "tagTypeComposition must not be null"
            ).trim();
            if (tagTypeComposition.isBlank()) {
                throw new IllegalArgumentException(
                        "tagTypeComposition must not be blank"
                );
            }
        }
    }
}

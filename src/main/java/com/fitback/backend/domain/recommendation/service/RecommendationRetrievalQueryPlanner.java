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

    public List<PlannedQuery> plan(ProductCategory category, List<TagInput> tags) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(tags, "tags must not be null");

        String silhouette = firstAlias(tags, TagType.SILHOUETTE, SILHOUETTE_ALIASES);
        String detail = firstAlias(tags, TagType.DETAIL, DETAIL_ALIASES);
        String material = firstAlias(tags, TagType.MATERIAL, MATERIAL_ALIASES);
        String color = firstAlias(tags, TagType.COLOR, COLOR_ALIASES);

        LinkedHashMap<String, PlannedQuery> semanticQueries = new LinkedHashMap<>();
        addSignalQueries(semanticQueries, silhouette, "SILHOUETTE", color);
        addSignalQueries(semanticQueries, detail, "DETAIL", color);
        addSignalQueries(semanticQueries, material, "MATERIAL", color);

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

    private static void addSignalQueries(
            Map<String, PlannedQuery> queries,
            String signal,
            String composition,
            String color
    ) {
        if (signal == null) {
            return;
        }
        queries.putIfAbsent(signal, new PlannedQuery(signal, composition));
        if (color != null) {
            String refined = signal + " " + color;
            queries.putIfAbsent(
                    refined,
                    new PlannedQuery(refined, composition + "+COLOR")
            );
        }
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

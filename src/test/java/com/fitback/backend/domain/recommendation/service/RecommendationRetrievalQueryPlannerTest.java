package com.fitback.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.recommendation.service.RecommendationRetrievalQueryPlanner.PlannedQuery;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot.TagInput;
import com.fitback.backend.domain.tag.entity.TagType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RecommendationRetrievalQueryPlannerTest {

    private final RecommendationRetrievalQueryPlanner planner =
            new RecommendationRetrievalQueryPlanner();

    @Test
    void plansOneQueryPerAttributeTypeBeforeAddingColorRefinements() {
        // 실루엣/디테일/소재가 모두 태그된 경우, "속성+색상" 조합을 앞 타입부터
        // 채우다 예산이 차서 뒤 타입(소재)의 검색어 자체가 통째로 밀려나면 안 된다 —
        // 세 속성 모두 단독 검색어를 먼저 확보하고, 남는 예산에만 색상 조합을 채운다.
        List<PlannedQuery> queries = planner.plan(
                ProductCategory.DRESS,
                List.of(
                        tag(1, "A라인", TagType.SILHOUETTE),
                        tag(2, "네이비", TagType.COLOR),
                        tag(3, "브이넥", TagType.DETAIL),
                        tag(4, "코튼", TagType.MATERIAL)
                )
        );

        assertThat(queries)
                .extracting(PlannedQuery::keyword, PlannedQuery::tagTypeComposition)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("a-line", "SILHOUETTE"),
                        org.assertj.core.groups.Tuple.tuple("v-neck", "DETAIL"),
                        org.assertj.core.groups.Tuple.tuple("cotton", "MATERIAL"),
                        org.assertj.core.groups.Tuple.tuple(
                                "a-line navy",
                                "SILHOUETTE+COLOR"
                        ),
                        org.assertj.core.groups.Tuple.tuple("", "CATEGORY")
                );
    }

    @Test
    void neverDropsAnAttributeTypeWhenAllThreeAndColorArePresent() {
        // 팀원이 보고한 실제 재현 케이스: 오버사이즈+그레이+라운드넥+니트에서
        // 니트(소재)가 검색에서 통째로 빠지던 문제. 세 속성 타입 모두 최소 한 개의
        // 검색어를 반드시 갖는지 확인한다.
        List<PlannedQuery> queries = planner.plan(
                ProductCategory.TOP,
                List.of(
                        tag(1, "오버사이즈", TagType.SILHOUETTE),
                        tag(2, "그레이", TagType.COLOR),
                        tag(3, "라운드넥", TagType.DETAIL),
                        tag(4, "니트", TagType.MATERIAL)
                )
        );

        assertThat(queries)
                .extracting(PlannedQuery::tagTypeComposition)
                .contains("SILHOUETTE", "DETAIL", "MATERIAL");
        assertThat(queries)
                .extracting(PlannedQuery::keyword)
                .contains("oversized", "crewneck", "knit");
    }

    @Test
    void usesColorAsStandaloneQueryWhenNoOtherAttributeTagIsPresent() {
        // 색상만 태그되고 실루엣/디테일/소재가 하나도 없으면, 색상이 다른 속성의
        // 보정용으로만 쓰이던 이전 동작 때문에 검색어가 전혀 안 만들어졌었다.
        List<PlannedQuery> queries = planner.plan(
                ProductCategory.TOP,
                List.of(tag(1, "네이비", TagType.COLOR))
        );

        assertThat(queries)
                .containsExactly(
                        new PlannedQuery("navy", "COLOR"),
                        new PlannedQuery("", "CATEGORY")
                );
    }

    @Test
    void usesMaterialAliasAndColorOnlyAsRefinement() {
        List<PlannedQuery> queries = planner.plan(
                ProductCategory.BOTTOM,
                List.of(
                        tag(1, "데님", TagType.MATERIAL),
                        tag(2, "블랙", TagType.COLOR)
                )
        );

        assertThat(queries)
                .extracting(PlannedQuery::keyword, PlannedQuery::tagTypeComposition)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("denim", "MATERIAL"),
                        org.assertj.core.groups.Tuple.tuple(
                                "denim black",
                                "MATERIAL+COLOR"
                        ),
                        org.assertj.core.groups.Tuple.tuple("", "CATEGORY")
                );
        assertThat(queries)
                .noneMatch(query -> query.tagTypeComposition().equals("COLOR"));
    }

    @Test
    void excludesStyleAndUnmappedCanonicalTagsWithoutRawFallback() {
        List<PlannedQuery> queries = planner.plan(
                ProductCategory.OUTER,
                List.of(
                        tag(1, "미니멀", TagType.STYLE),
                        tag(2, "H라인", TagType.SILHOUETTE),
                        tag(3, "우븐/시어", TagType.MATERIAL),
                        tag(4, "턱", TagType.DETAIL),
                        tag(5, "파스텔/메탈릭", TagType.COLOR)
                )
        );

        assertThat(queries)
                .containsExactly(new PlannedQuery("", "CATEGORY"));
    }

    @Test
    void deduplicatesRepeatedTagsAndUsesFirstMappedAliasPerType() {
        List<PlannedQuery> queries = planner.plan(
                ProductCategory.TOP,
                List.of(
                        tag(1, "오버사이즈", TagType.SILHOUETTE),
                        tag(2, "오버사이즈", TagType.SILHOUETTE),
                        tag(3, "슬림핏", TagType.SILHOUETTE),
                        tag(4, "화이트", TagType.COLOR),
                        tag(5, "화이트", TagType.COLOR)
                )
        );

        assertThat(queries)
                .extracting(PlannedQuery::keyword)
                .containsExactly("oversized", "oversized white", "");
    }

    @Test
    void isDeterministicAndNeverExceedsFiveQueries() {
        List<TagInput> tags = List.of(
                tag(1, "A라인", TagType.SILHOUETTE),
                tag(2, "네이비", TagType.COLOR),
                tag(3, "브이넥", TagType.DETAIL),
                tag(4, "코튼", TagType.MATERIAL)
        );

        List<PlannedQuery> first = planner.plan(ProductCategory.DRESS, tags);
        List<PlannedQuery> second = planner.plan(ProductCategory.DRESS, tags);

        assertThat(first).isEqualTo(second).hasSize(5);
        assertThat(first.getLast()).isEqualTo(new PlannedQuery("", "CATEGORY"));
    }

    @Test
    void returnsOnlyCategoryFallbackForEmptyEligibleTags() {
        assertThat(planner.plan(ProductCategory.TOP, List.of()))
                .containsExactly(new PlannedQuery("", "CATEGORY"));
    }

    @Test
    void neverEmitsKoreanCanonicalNameInKeyword() {
        List<PlannedQuery> queries = planner.plan(
                ProductCategory.DRESS,
                List.of(
                        tag(1, "롱기장", TagType.SILHOUETTE),
                        tag(2, "러플/프릴", TagType.DETAIL),
                        tag(3, "시폰", TagType.MATERIAL),
                        tag(4, "베이지", TagType.COLOR),
                        tag(5, "캐주얼", TagType.STYLE)
                )
        );

        assertThat(queries)
                .extracting(PlannedQuery::keyword)
                .allMatch(keyword -> !keyword.matches(".*[가-힣].*"))
                .doesNotContain("롱기장", "러플/프릴", "시폰", "베이지", "캐주얼");
    }

    @ParameterizedTest
    @CsvSource({
            "와이드핏, wide-leg",
            "슬림핏, slim-fit",
            "오버사이즈, oversized",
            "레귤러핏, regular-fit",
            "A라인, a-line",
            "크롭, cropped",
            "로우라이즈, low-rise",
            "하이라이즈, high-rise",
            "미디기장, midi",
            "롱기장, maxi"
    })
    void mapsCuratedSilhouetteAliases(String canonicalName, String alias) {
        assertThat(planner.plan(
                ProductCategory.DRESS,
                List.of(tag(1, canonicalName, TagType.SILHOUETTE))
        )).first().isEqualTo(new PlannedQuery(alias, "SILHOUETTE"));
    }

    @ParameterizedTest
    @CsvSource({
            "브이넥, v-neck",
            "터틀넥, turtleneck",
            "라운드넥, crewneck",
            "러플/프릴, ruffle",
            "지퍼, zip",
            "벨트, belted",
            "포켓, pocket",
            "슬릿, slit",
            "단추, button"
    })
    void mapsCuratedDetailAliases(String canonicalName, String alias) {
        assertThat(planner.plan(
                ProductCategory.TOP,
                List.of(tag(1, canonicalName, TagType.DETAIL))
        )).first().isEqualTo(new PlannedQuery(alias, "DETAIL"));
    }

    @ParameterizedTest
    @CsvSource({
            "데님, denim",
            "니트, knit",
            "코튼, cotton",
            "린넨, linen",
            "가죽, leather",
            "트위드, tweed",
            "시폰, chiffon"
    })
    void mapsCuratedMaterialAliases(String canonicalName, String alias) {
        assertThat(planner.plan(
                ProductCategory.BOTTOM,
                List.of(tag(1, canonicalName, TagType.MATERIAL))
        )).first().isEqualTo(new PlannedQuery(alias, "MATERIAL"));
    }

    @ParameterizedTest
    @CsvSource({
            "화이트, white",
            "블랙, black",
            "베이지, beige",
            "네이비, navy",
            "그레이, gray",
            "브라운, brown",
            "카키, khaki"
    })
    void mapsCuratedColorAliasesOnlyAsRefinement(String canonicalName, String alias) {
        assertThat(planner.plan(
                ProductCategory.DRESS,
                List.of(
                        tag(1, "A라인", TagType.SILHOUETTE),
                        tag(2, canonicalName, TagType.COLOR)
                )
        )).element(1).isEqualTo(new PlannedQuery(
                "a-line " + alias,
                "SILHOUETTE+COLOR"
        ));
    }

    private static TagInput tag(long id, String name, TagType type) {
        return new TagInput(id, name, type);
    }
}

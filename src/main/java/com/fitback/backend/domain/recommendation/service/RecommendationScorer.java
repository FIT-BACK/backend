package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.recommendation.entity.RecommendationReasonCode;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot.TagInput;
import com.fitback.backend.domain.tag.entity.TagType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

@Component
public class RecommendationScorer {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal IMAGE_SIMILARITY_WEIGHT = new BigDecimal("0.7");
    private static final BigDecimal TAG_MATCH_WEIGHT = new BigDecimal("0.3");
    private static final BigDecimal HIGH_SIMILARITY_THRESHOLD = new BigDecimal("80");
    private static final Set<TagType> ATTRIBUTE_TAG_TYPES = EnumSet.of(
            TagType.SILHOUETTE,
            TagType.MATERIAL,
            TagType.DETAIL,
            TagType.COLOR
    );
    // Fashion-CLIP 실제 이미지 유사도 연동 전까지는 temporaryImageSimilarityScore가
    // 한 요청 안의 모든 후보에 동일하게 적용되는 더미 값이라(실제 후보별 이미지 비교가
    // 아직 없음), 사실상 태그 일치 점수(tagMatchScore)만으로 순위가 갈린다. 그런데
    // SILHOUETTE/MATERIAL/DETAIL/COLOR를 전부 동일 가중치로 다루다 보니, 색상이 안
    // 맞아도 다른 속성 몇 개가 맞으면 색상 안 맞는 옷이 더 위로 올라오는 문제가 있었음
    // — 실사용 중 "컬러도 안 맞는데 왜 이게 위에 뜨냐"는 피드백으로 확인됨.
    // 색상을 다른 속성보다 훨씬 크게 가중해서, 색상이 맞는 후보가 색상이 안 맞는
    // 후보보다 (다른 속성이 전부 어긋나더라도) 항상 우선하도록 한다.
    private static final Map<TagType, Integer> ATTRIBUTE_TAG_WEIGHTS = new EnumMap<>(Map.of(
            TagType.COLOR, 6,
            TagType.SILHOUETTE, 1,
            TagType.MATERIAL, 1,
            TagType.DETAIL, 1
    ));

    private final RecommendationRetrievalQueryPlanner queryPlanner;

    public RecommendationScorer(RecommendationRetrievalQueryPlanner queryPlanner) {
        this.queryPlanner = queryPlanner;
    }

    public Score score(
            List<TagInput> tags,
            BigDecimal temporaryImageSimilarityScore,
            ExternalProductCandidate candidate
    ) {
        validateTemporaryImageSimilarityScore(temporaryImageSimilarityScore);
        String searchableText = searchableText(candidate);
        List<TagInput> attributeTags = tags.stream()
                .filter(tag -> ATTRIBUTE_TAG_TYPES.contains(tag.tagType()))
                .toList();
        // 검색 쪽(RecommendationRetrievalQueryPlanner)이 한국어 태그를 영어 별칭으로
        // 바꿔서 상품을 찾아오는 것과 달리, 여기는 원래 한국어 태그명을 영어 상품
        // 텍스트(searchableText)에 그대로 substring 매칭해서 항상 거의 매칭 실패
        // 했었다 — 검색 쪽과 동일한 별칭 테이블을 재사용해 같은 기준으로 비교한다.
        // 큐레이션된 별칭이 없는 태그(예: STYLE)는 검증 불가로 보고 매칭에서 제외한다.
        List<TagInput> matchedAttributeTags = attributeTags.stream()
                .filter(tag -> isMatched(tag, searchableText))
                .toList();
        long matchedTagCount = matchedAttributeTags.size();
        BigDecimal tagMatchScore = calculateWeightedTagMatchScore(
                attributeTags,
                matchedAttributeTags
        );
        BigDecimal similarityScore = calculateWeightedSimilarityScore(
                temporaryImageSimilarityScore,
                tagMatchScore
        );
        Set<String> reasonCodes = new TreeSet<>();
        int totalTagCount = attributeTags.size();
        if (totalTagCount == 0) {
            reasonCodes.add(RecommendationReasonCode.NO_SCORABLE_TAGS.name());
        } else if (matchedTagCount == 0) {
            reasonCodes.add(RecommendationReasonCode.NO_ATTRIBUTE_MATCH.name());
        } else if (matchedTagCount == totalTagCount) {
            reasonCodes.add(RecommendationReasonCode.FULL_ATTRIBUTE_MATCH.name());
        } else {
            reasonCodes.add(RecommendationReasonCode.PARTIAL_ATTRIBUTE_MATCH.name());
        }
        if (totalTagCount > 0
                && tagMatchScore.compareTo(HIGH_SIMILARITY_THRESHOLD) >= 0) {
            reasonCodes.add(RecommendationReasonCode.HIGH_SIMILARITY.name());
        }
        return new Score(similarityScore, List.copyOf(reasonCodes));
    }

    private static BigDecimal calculateWeightedTagMatchScore(
            List<TagInput> attributeTags,
            List<TagInput> matchedAttributeTags
    ) {
        int totalWeight = attributeTags.stream()
                .mapToInt(tag -> weightOf(tag.tagType()))
                .sum();
        if (totalWeight == 0) {
            return ONE_HUNDRED.setScale(2);
        }
        int matchedWeight = matchedAttributeTags.stream()
                .mapToInt(tag -> weightOf(tag.tagType()))
                .sum();
        return BigDecimal.valueOf(matchedWeight)
                .multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(totalWeight), 2, RoundingMode.HALF_UP);
    }

    private static int weightOf(TagType tagType) {
        return ATTRIBUTE_TAG_WEIGHTS.getOrDefault(tagType, 1);
    }

    private boolean isMatched(TagInput tag, String searchableText) {
        String alias = queryPlanner.aliasFor(tag);
        if (alias != null) {
            return searchableText.contains(alias.toLowerCase(Locale.ROOT));
        }
        return searchableText.contains(tag.name().toLowerCase(Locale.ROOT));
    }

    private static BigDecimal calculateWeightedSimilarityScore(
            BigDecimal temporaryImageSimilarityScore,
            BigDecimal tagMatchScore
    ) {
        return temporaryImageSimilarityScore.multiply(IMAGE_SIMILARITY_WEIGHT)
                .add(tagMatchScore.multiply(TAG_MATCH_WEIGHT))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static void validateTemporaryImageSimilarityScore(
            BigDecimal temporaryImageSimilarityScore
    ) {
        if (temporaryImageSimilarityScore == null
                || temporaryImageSimilarityScore.compareTo(BigDecimal.ZERO) < 0
                || temporaryImageSimilarityScore.compareTo(ONE_HUNDRED) > 0) {
            throw new IllegalArgumentException(
                    "temporaryImageSimilarityScore must be between 0 and 100"
            );
        }
    }

    private static String searchableText(ExternalProductCandidate candidate) {
        return String.join(
                " ",
                candidate.name(),
                nullable(candidate.brand()),
                nullable(candidate.categoryPath())
        ).toLowerCase(Locale.ROOT);
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }

    public record Score(BigDecimal similarityScore, List<String> reasonCodes) {
    }
}

package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.recommendation.entity.RecommendationReasonCode;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot.TagInput;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

@Component
public class RecommendationScorer {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal IMAGE_SIMILARITY_WEIGHT = new BigDecimal("0.7");
    private static final BigDecimal TAG_MATCH_WEIGHT = new BigDecimal("0.3");
    private static final BigDecimal HIGH_SIMILARITY_THRESHOLD = new BigDecimal("80");
    public Score score(
            List<TagInput> tags,
            BigDecimal temporaryImageSimilarityScore,
            ExternalProductCandidate candidate
    ) {
        validateTemporaryImageSimilarityScore(temporaryImageSimilarityScore);
        RecommendationTagMatcher.Match match = RecommendationTagMatcher.match(tags, candidate);
        BigDecimal tagMatchScore = calculateTagMatchScore(
                match.matchedTagCount(),
                match.eligibleTagCount()
        );
        BigDecimal similarityScore = calculateWeightedSimilarityScore(
                temporaryImageSimilarityScore,
                tagMatchScore
        );
        Set<String> reasonCodes = new TreeSet<>();
        int totalTagCount = match.eligibleTagCount();
        long matchedTagCount = match.matchedTagCount();
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

    private static BigDecimal calculateTagMatchScore(
            long matchedTagCount,
            int totalTagCount
    ) {
        if (totalTagCount == 0) {
            return ONE_HUNDRED.setScale(2);
        }
        return BigDecimal.valueOf(matchedTagCount)
                .multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(totalTagCount), 2, RoundingMode.HALF_UP);
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

    public record Score(BigDecimal similarityScore, List<String> reasonCodes) {
    }
}

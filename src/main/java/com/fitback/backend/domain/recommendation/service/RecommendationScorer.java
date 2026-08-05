package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot.TagInput;
import com.fitback.backend.domain.tag.entity.TagType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

@Component
public class RecommendationScorer {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal HIGH_SIMILARITY_THRESHOLD = new BigDecimal("80");
    private static final Set<TagType> ATTRIBUTE_TAG_TYPES = EnumSet.of(
            TagType.SILHOUETTE,
            TagType.MATERIAL,
            TagType.DETAIL,
            TagType.COLOR
    );

    public Score score(
            List<TagInput> tags,
            ExternalProductCandidate candidate
    ) {
        String searchableText = searchableText(candidate);
        List<TagInput> attributeTags = tags.stream()
                .filter(tag -> ATTRIBUTE_TAG_TYPES.contains(tag.tagType()))
                .toList();
        long matchedTagCount = attributeTags.stream()
                .map(TagInput::name)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .filter(searchableText::contains)
                .count();
        BigDecimal similarityScore = calculateSimilarityScore(
                matchedTagCount,
                attributeTags.size()
        );
        Set<String> reasonCodes = new TreeSet<>();
        if (similarityScore.compareTo(HIGH_SIMILARITY_THRESHOLD) >= 0) {
            reasonCodes.add("HIGH_SIMILARITY");
        }
        if (matchedTagCount > 0) {
            reasonCodes.add("TAG_MATCH");
        }
        return new Score(similarityScore, List.copyOf(reasonCodes));
    }

    private static BigDecimal calculateSimilarityScore(
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

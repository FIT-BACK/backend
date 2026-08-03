package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot.TagInput;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class RecommendationScorer {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal TAG_MATCH_SCORE = new BigDecimal("70");
    private static final BigDecimal HIGH_SIMILARITY_THRESHOLD = new BigDecimal("80");

    public Score score(
            List<TagInput> tags,
            List<String> customTagNames,
            ExternalProductCandidate candidate
    ) {
        boolean tagMatched = Stream.concat(
                        tags.stream().map(TagInput::name),
                        customTagNames.stream()
                )
                .map(tag -> tag.toLowerCase(Locale.ROOT))
                .anyMatch(tag -> searchableText(candidate).contains(tag));
        BigDecimal similarityScore = normalize(candidate.providerScore(), tagMatched);
        Set<String> reasonCodes = new TreeSet<>();
        if (similarityScore.compareTo(HIGH_SIMILARITY_THRESHOLD) >= 0) {
            reasonCodes.add("HIGH_SIMILARITY");
        }
        if (tagMatched) {
            reasonCodes.add("TAG_MATCH");
        }
        if (reasonCodes.isEmpty()) {
            reasonCodes.add("PROVIDER_SIMILARITY");
        }
        return new Score(similarityScore, List.copyOf(reasonCodes));
    }

    private static BigDecimal normalize(BigDecimal providerScore, boolean tagMatched) {
        if (providerScore == null) {
            return tagMatched ? TAG_MATCH_SCORE.setScale(2) : BigDecimal.ZERO.setScale(2);
        }
        BigDecimal normalized = providerScore.max(BigDecimal.ZERO)
                .min(ONE)
                .multiply(ONE_HUNDRED);
        return normalized.setScale(2, RoundingMode.HALF_UP);
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

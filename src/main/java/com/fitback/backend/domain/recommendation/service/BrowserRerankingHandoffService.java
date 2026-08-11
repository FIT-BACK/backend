package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.product.service.CandidateTokenService;
import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.recommendation.dto.BrowserRerankingCandidate;
import com.fitback.backend.domain.recommendation.dto.BrowserRerankingHandoff;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot.TagInput;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class BrowserRerankingHandoffService {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final int SIMILARITY_SCALE = 2;

    private final CandidateTokenService candidateTokenService;

    public BrowserRerankingHandoffService(CandidateTokenService candidateTokenService) {
        this.candidateTokenService = candidateTokenService;
    }

    public BrowserRerankingHandoff create(
            long memberId,
            ProductCategory category,
            List<TagInput> tags,
            List<ExternalProductCandidate> candidates
    ) {
        List<BrowserRerankingCandidate> handoffCandidates = candidates.stream()
                .limit(BrowserRerankingHandoff.MAX_CANDIDATES)
                .map(candidate -> new BrowserRerankingCandidate(
                        candidateTokenService.issue(memberId, candidate.providerRef()),
                        candidate.imageUrl(),
                        tagSimilarity(tags, candidate)
                ))
                .toList();
        return new BrowserRerankingHandoff(category, handoffCandidates);
    }

    static BigDecimal tagSimilarity(
            List<TagInput> tags,
            ExternalProductCandidate candidate
    ) {
        RecommendationTagMatcher.Match match = RecommendationTagMatcher.match(tags, candidate);
        if (match.eligibleTagCount() == 0) {
            return ONE;
        }
        return BigDecimal.valueOf(match.matchedTagCount())
                .divide(
                        BigDecimal.valueOf(match.eligibleTagCount()),
                        SIMILARITY_SCALE,
                        RoundingMode.HALF_UP
                );
    }
}

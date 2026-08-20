package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.product.service.CandidateTokenService;
import com.fitback.backend.domain.product.service.ProductCandidateMapper;
import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProductOffer;
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
    private final ProductCandidateMapper candidateMapper;
    private final RecommendationRetrievalQueryPlanner queryPlanner;

    public BrowserRerankingHandoffService(
            CandidateTokenService candidateTokenService,
            ProductCandidateMapper candidateMapper,
            RecommendationRetrievalQueryPlanner queryPlanner
    ) {
        this.candidateTokenService = candidateTokenService;
        this.candidateMapper = candidateMapper;
        this.queryPlanner = queryPlanner;
    }

    public BrowserRerankingHandoff create(
            long memberId,
            ProductCategory category,
            List<TagInput> tags,
            List<ExternalProductCandidate> candidates
    ) {
        List<BrowserRerankingCandidate> handoffCandidates = candidates.stream()
                .limit(BrowserRerankingHandoff.MAX_CANDIDATES)
                .map(candidate -> toHandoffCandidate(memberId, tags, candidate))
                .toList();
        return new BrowserRerankingHandoff(category, handoffCandidates);
    }

    private BrowserRerankingCandidate toHandoffCandidate(
            long memberId,
            List<TagInput> tags,
            ExternalProductCandidate candidate
    ) {
        ProductOffer offer = candidate.offer();
        return new BrowserRerankingCandidate(
                candidateTokenService.issue(memberId, candidate.providerRef()),
                candidate.imageUrl(),
                tagSimilarity(tags, candidate),
                candidate.name(),
                offer == null ? null : offer.seller(),
                offer == null ? null : candidateMapper.price(offer),
                offer == null ? null : offer.purchaseUrl()
        );
    }

    BigDecimal tagSimilarity(
            List<TagInput> tags,
            ExternalProductCandidate candidate
    ) {
        RecommendationTagMatcher.Match match = RecommendationTagMatcher.match(
                tags,
                candidate,
                queryPlanner
        );
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

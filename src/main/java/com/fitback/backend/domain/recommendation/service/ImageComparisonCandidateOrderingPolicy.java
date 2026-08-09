package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import java.util.List;

public interface ImageComparisonCandidateOrderingPolicy {

    List<ExternalProductCandidate> order(
            List<List<ExternalProductCandidate>> candidateBatches,
            int multiTagPriorityLimit
    );
}

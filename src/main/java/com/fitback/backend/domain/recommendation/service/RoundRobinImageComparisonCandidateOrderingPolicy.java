package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class RoundRobinImageComparisonCandidateOrderingPolicy
        implements ImageComparisonCandidateOrderingPolicy {

    @Override
    public List<ExternalProductCandidate> order(
            List<List<ExternalProductCandidate>> candidateBatches
    ) {
        Objects.requireNonNull(
                candidateBatches,
                "candidateBatches must not be null"
        );

        List<ExternalProductCandidate> orderedCandidates = new ArrayList<>();

        // 앞쪽 검색어의 후보 점유 방지를 위한 검색 결과별 동일 순위 교차 배치
        for (int rank = 0; ; rank++) {
            // 가장 긴 검색 결과까지 모두 확인한 시점 판단을 위한 현재 순위 후보 존재 여부
            boolean candidateFoundAtRank = false;

            for (List<ExternalProductCandidate> batch : candidateBatches) {
                // 먼저 소진된 검색 결과를 건너뛰고 남은 검색 결과 탐색 유지
                if (rank >= batch.size()) {
                    continue;
                }

                candidateFoundAtRank = true;
                orderedCandidates.add(batch.get(rank));
            }

            // 모든 검색 결과 소진 후 추가 순위 탐색 방지
            if (!candidateFoundAtRank) {
                break;
            }
        }

        return List.copyOf(orderedCandidates);
    }
}

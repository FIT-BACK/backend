package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class VisionCandidateSelector {

    private final int candidateLimit;

    public VisionCandidateSelector(int candidateLimit) {
        if (candidateLimit < 1) {
            throw new IllegalArgumentException("candidateLimit must be positive");
        }
        this.candidateLimit = candidateLimit;
    }

    public SelectionResult select(
            List<List<ExternalProductCandidate>> candidateBatches
    ) {
        Objects.requireNonNull(
                candidateBatches,
                "candidateBatches must not be null"
        );

        // 후보 순서 유지와 공급자 식별자 기준 중복 제거를 위한 별도 자료구조
        List<ExternalProductCandidate> selectedCandidates = new ArrayList<>();
        Set<ProviderProductRef> selectedProductRefs = new HashSet<>();
        boolean unsupportedReferenceSkipped = false;

        // 검색어별 후보 편중 방지를 위한 동일 순위 교차 선택
        for (int rank = 0; selectedCandidates.size() < candidateLimit; rank++) {
            // 가장 긴 검색 결과까지 모두 확인한 시점 판단을 위한 현재 순위 후보 존재 여부
            boolean candidateFoundAtRank = false;

            for (List<ExternalProductCandidate> batch : candidateBatches) {
                Objects.requireNonNull(
                        batch,
                        "candidate batch must not be null"
                );

                if (rank >= batch.size()) {
                    continue;
                }

                candidateFoundAtRank = true;
                ExternalProductCandidate candidate = Objects.requireNonNull(
                        batch.get(rank),
                        "candidate must not be null"
                );

                // 검색 응답의 이미지 URL만 사용하는 후보 선별 단계의 원격 이미지 접근 방지
                if (candidate.imageUrl() == null) {
                    continue;
                }

                // 추천 상품 저장 단계에서 사용할 수 없는 불안정 공급자 식별자 제외
                if (!candidate.providerRef().stable()) {
                    unsupportedReferenceSkipped = true;
                    continue;
                }

                // 여러 태그 검색 결과에 포함된 동일 상품의 중복 이미지 비교 방지
                if (!selectedProductRefs.add(candidate.providerRef())) {
                    continue;
                }

                selectedCandidates.add(candidate);

                if (selectedCandidates.size() == candidateLimit) {
                    break;
                }
            }

            // 모든 검색 결과에 현재 순위의 후보가 없을 때 라운드 로빈 종료
            if (!candidateFoundAtRank) {
                break;
            }
        }

        return new SelectionResult(
                selectedCandidates,
                unsupportedReferenceSkipped
        );
    }

    public record SelectionResult(
            List<ExternalProductCandidate> candidates,
            boolean unsupportedReferenceSkipped
    ) {

        public SelectionResult {
            // 호출 측 변경에 따른 선별 결과 변형 방지
            candidates = List.copyOf(
                    Objects.requireNonNull(
                            candidates,
                            "candidates must not be null"
                    )
            );
        }
    }
}

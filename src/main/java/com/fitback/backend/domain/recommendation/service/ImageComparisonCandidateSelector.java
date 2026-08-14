package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ImageComparisonCandidateSelector {

    private final ImageComparisonCandidateOrderingPolicy orderingPolicy;
    private final int candidateLimit;

    public ImageComparisonCandidateSelector(
            ImageComparisonCandidateOrderingPolicy orderingPolicy,
            @Value("${recommendation.image-comparison-candidate-limit}") int candidateLimit
    ) {
        this.orderingPolicy = Objects.requireNonNull(
                orderingPolicy,
                "orderingPolicy must not be null"
        );
        // 이미지 비교 대상이 항상 비어지는 잘못된 운영 설정 차단
        if (candidateLimit < 1) {
            throw new IllegalArgumentException("candidateLimit must be positive");
        }
        this.candidateLimit = candidateLimit;
    }

    public SelectionResult select(
            List<List<ExternalProductCandidate>> candidateBatches
    ) {
        int inputCandidateCount = candidateBatches.stream()
                .mapToInt(List::size)
                .sum();
        List<ExternalProductCandidate> orderedCandidates = orderingPolicy.order(
                candidateBatches,
                candidateLimit
        );

        // 채택 순서 유지와 공급자 식별자 기준 중복 제거를 위한 자료구조 분리
        List<ExternalProductCandidate> selectedCandidates = new ArrayList<>();
        Set<ProviderProductRef> selectedProductRefs = new HashSet<>();

        // 선별 단계에서 제외된 불안정 식별자의 기존 경고 계약 전달
        boolean unsupportedReferenceSkipped = false;
        int invalidProviderReferenceDropCount = 0;
        int missingImageUrlDropCount = 0;
        int duplicateDropCount = 0;
        int limitOverflowDropCount = 0;

        // 순서 정책과 무관하게 동일한 이미지 비교 가능 조건 적용
        for (int index = 0; index < orderedCandidates.size(); index++) {
            ExternalProductCandidate candidate = orderedCandidates.get(index);
            // 추천 상품 저장 단계에서 사용할 수 없는 불안정 공급자 식별자 제외
            if (!candidate.providerRef().stable()) {
                unsupportedReferenceSkipped = true;
                invalidProviderReferenceDropCount++;
                continue;
            }

            // 검색 응답의 이미지 URL만 사용하는 후보 선별 단계의 원격 이미지 접근 방지
            if (candidate.imageUrl() == null) {
                missingImageUrlDropCount++;
                continue;
            }

            // 여러 태그 검색 결과에 포함된 동일 상품의 중복 이미지 비교 방지
            if (!selectedProductRefs.add(candidate.providerRef())) {
                duplicateDropCount++;
                continue;
            }

            // 모든 제외 조건을 통과한 후보만 이미지 비교 처리 예산에 포함
            selectedCandidates.add(candidate);

            // 최대 후보 수 초과와 불필요한 추가 순회 방지를 위한 즉시 종료
            if (selectedCandidates.size() == candidateLimit) {
                // 현재 계약상 이후 후보는 다른 drop 조건을 평가하지 않고 limit 도달로만 건너뜀
                limitOverflowDropCount = orderedCandidates.size() - index - 1;
                break;
            }
        }

        return new SelectionResult(
                selectedCandidates,
                unsupportedReferenceSkipped,
                new SelectionMetrics(
                        inputCandidateCount,
                        orderedCandidates.size(),
                        selectedCandidates.size(),
                        invalidProviderReferenceDropCount,
                        missingImageUrlDropCount,
                        duplicateDropCount,
                        limitOverflowDropCount,
                        0
                )
        );
    }

    public record SelectionResult(
            List<ExternalProductCandidate> candidates,
            boolean unsupportedReferenceSkipped,
            SelectionMetrics metrics
    ) {

        public SelectionResult {
            // 호출 측 변경에 따른 선별 결과 변형 방지
            candidates = List.copyOf(
                    Objects.requireNonNull(
                            candidates,
                            "candidates must not be null"
                    )
            );
            metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        }

        public SelectionResult(
                List<ExternalProductCandidate> candidates,
                boolean unsupportedReferenceSkipped
        ) {
            this(
                    candidates,
                    unsupportedReferenceSkipped,
                    SelectionMetrics.empty(candidates.size())
            );
        }
    }

    public record SelectionMetrics(
            int inputCandidateCount,
            int orderedCandidateCount,
            int outputCandidateCount,
            int invalidProviderReferenceDropCount,
            int missingImageUrlDropCount,
            int duplicateDropCount,
            int limitOverflowDropCount,
            int otherDropCount
    ) {

        private static SelectionMetrics empty(int outputCandidateCount) {
            return new SelectionMetrics(
                    0,
                    0,
                    outputCandidateCount,
                    0,
                    0,
                    0,
                    0,
                    0
            );
        }
    }
}

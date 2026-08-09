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
        // 전체 비교 예산의 3분의 1만 다중 태그 후보에 우선 배정해 나머지 다양성 보존
        int multiTagPriorityLimit = candidateLimit / 3;
        List<ExternalProductCandidate> orderedCandidates = orderingPolicy.order(
                candidateBatches,
                multiTagPriorityLimit
        );

        // 채택 순서 유지와 공급자 식별자 기준 중복 제거를 위한 자료구조 분리
        List<ExternalProductCandidate> selectedCandidates = new ArrayList<>();
        Set<ProviderProductRef> selectedProductRefs = new HashSet<>();

        // 선별 단계에서 제외된 불안정 식별자의 기존 경고 계약 전달
        boolean unsupportedReferenceSkipped = false;

        // 순서 정책과 무관하게 동일한 이미지 비교 가능 조건 적용
        for (ExternalProductCandidate candidate : orderedCandidates) {
            // 추천 상품 저장 단계에서 사용할 수 없는 불안정 공급자 식별자 제외
            if (!candidate.providerRef().stable()) {
                unsupportedReferenceSkipped = true;
                continue;
            }

            // 검색 응답의 이미지 URL만 사용하는 후보 선별 단계의 원격 이미지 접근 방지
            if (candidate.imageUrl() == null) {
                continue;
            }

            // 여러 태그 검색 결과에 포함된 동일 상품의 중복 이미지 비교 방지
            if (!selectedProductRefs.add(candidate.providerRef())) {
                continue;
            }

            // 모든 제외 조건을 통과한 후보만 이미지 비교 처리 예산에 포함
            selectedCandidates.add(candidate);

            // 최대 후보 수 초과와 불필요한 추가 순회 방지를 위한 즉시 종료
            if (selectedCandidates.size() == candidateLimit) {
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

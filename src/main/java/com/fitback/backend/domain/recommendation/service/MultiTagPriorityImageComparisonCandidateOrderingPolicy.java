package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MultiTagPriorityImageComparisonCandidateOrderingPolicy
        implements ImageComparisonCandidateOrderingPolicy {

    private static final int MULTI_TAG_PRIORITY_SLOT_DIVISOR = 3;
    private static final Comparator<CandidateOccurrence> PRIORITY_ORDER = Comparator
            // 여러 태그 검색에 함께 잡힌 상품부터 비교하기 위한 검색 배치 등장 횟수 우선
            .comparingInt(CandidateOccurrence::batchCount)
            .reversed()
            // 등장 횟수가 같으면 Shopify 검색에서 한 번이라도 높게 노출된 상품 우선
            .thenComparingInt(CandidateOccurrence::bestRank)
            // 동일 입력에서 항상 같은 결과를 만들기 위한 공급자 상품 식별자 고정 정렬
            .thenComparing(occurrence -> occurrence.candidate().providerRef().provider())
            .thenComparing(occurrence -> occurrence.candidate().providerRef().externalProductId())
            .thenComparing(
                    occurrence -> occurrence.candidate().providerRef().externalVariantId(),
                    Comparator.nullsFirst(Comparator.naturalOrder())
            )
            .thenComparing(
                    occurrence -> occurrence.candidate().providerRef().merchantId(),
                    Comparator.nullsFirst(Comparator.naturalOrder())
            );

    @Override
    public List<ExternalProductCandidate> order(
            List<List<ExternalProductCandidate>> candidateBatches,
            int candidateLimit
    ) {
        Objects.requireNonNull(
                candidateBatches,
                "candidateBatches must not be null"
        );
        if (candidateLimit < 1) {
            throw new IllegalArgumentException("candidateLimit must be positive");
        }

        // 검색 결과별 중복 등장을 태그 간 연관성 신호로 사용하기 위한 상품별 통계 집계
        Map<ProviderProductRef, CandidateOccurrence> occurrences = collectOccurrences(
                candidateBatches
        );

        List<ExternalProductCandidate> orderedCandidates = new ArrayList<>();
        Set<ProviderProductRef> prioritizedProductRefs = new HashSet<>();

        // 전체 후보의 일부만 다중 태그 신호로 우선 배치해 이미지 비교 후보 다양성 보존
        int multiTagPriorityLimit = candidateLimit / MULTI_TAG_PRIORITY_SLOT_DIVISOR;
        occurrences.values().stream()
                .filter(occurrence -> occurrence.batchCount() >= 2)
                .sorted(PRIORITY_ORDER)
                .limit(multiTagPriorityLimit)
                .forEach(occurrence -> {
                    orderedCandidates.add(occurrence.candidate());
                    prioritizedProductRefs.add(occurrence.candidate().providerRef());
                });

        // 우선 슬롯 이후에는 특정 태그 검색 결과의 후보 독점을 막는 라운드 로빈 적용
        appendRoundRobinCandidates(
                candidateBatches,
                prioritizedProductRefs,
                orderedCandidates
        );

        return List.copyOf(orderedCandidates);
    }

    private static Map<ProviderProductRef, CandidateOccurrence> collectOccurrences(
            List<List<ExternalProductCandidate>> candidateBatches
    ) {
        Map<ProviderProductRef, CandidateOccurrence> occurrences = new HashMap<>();

        for (List<ExternalProductCandidate> batch : candidateBatches) {
            // 같은 검색 결과 안의 중복은 여러 태그에 걸린 것으로 계산하지 않기 위한 배치별 기록
            Set<ProviderProductRef> countedProductRefs = new HashSet<>();

            for (int rank = 0; rank < batch.size(); rank++) {
                ExternalProductCandidate candidate = batch.get(rank);

                // 실제 이미지 비교에 사용할 수 없는 후보의 우선 슬롯 점유 방지
                if (!candidate.providerRef().stable() || candidate.imageUrl() == null) {
                    continue;
                }
                if (!countedProductRefs.add(candidate.providerRef())) {
                    continue;
                }

                int candidateRank = rank;
                occurrences.compute(
                        candidate.providerRef(),
                        (providerRef, occurrence) -> occurrence == null
                                ? CandidateOccurrence.first(candidate, candidateRank)
                                : occurrence.next(candidate, candidateRank)
                );
            }
        }

        return occurrences;
    }

    private static void appendRoundRobinCandidates(
            List<List<ExternalProductCandidate>> candidateBatches,
            Set<ProviderProductRef> prioritizedProductRefs,
            List<ExternalProductCandidate> orderedCandidates
    ) {
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
                ExternalProductCandidate candidate = batch.get(rank);

                // 우선 슬롯에 이미 들어간 상품의 라운드 로빈 재배치 방지
                if (prioritizedProductRefs.contains(candidate.providerRef())) {
                    continue;
                }
                orderedCandidates.add(candidate);
            }

            // 모든 검색 결과 소진 후 추가 순위 탐색 방지
            if (!candidateFoundAtRank) {
                break;
            }
        }
    }

    private record CandidateOccurrence(
            ExternalProductCandidate candidate,
            int batchCount,
            int bestRank
    ) {

        private static CandidateOccurrence first(
                ExternalProductCandidate candidate,
                int rank
        ) {
            return new CandidateOccurrence(candidate, 1, rank);
        }

        private CandidateOccurrence next(
                ExternalProductCandidate nextCandidate,
                int nextRank
        ) {
            if (nextRank < bestRank) {
                return new CandidateOccurrence(nextCandidate, batchCount + 1, nextRank);
            }
            return new CandidateOccurrence(candidate, batchCount + 1, bestRank);
        }
    }
}

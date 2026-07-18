package com.fitback.backend.domain.trend.service;

import com.fitback.backend.domain.trend.dto.TrendResponse;
import com.fitback.backend.domain.trend.entity.TrendContent;
import com.fitback.backend.domain.trend.repository.TrendContentRepository;
import com.fitback.backend.domain.trend.repository.TrendTagRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TrendService {

    private static final int TREND_PAGE_SIZE = 10;
    private static final Pageable TREND_PAGE_REQUEST = PageRequest.of(0, TREND_PAGE_SIZE + 1);

    private final TrendContentRepository trendContentRepository;
    private final TrendTagRepository trendTagRepository;

    // 트렌드 목록 조회
    @Transactional(readOnly = true)
    public TrendResponse.TrendList getTrends(Long cursor) {

        // cursor 기준 다음 페이지 분량의 트렌드 목록 조회
        List<TrendContent> trendPage = findTrendPage(cursor);

        // 다음 페이지 존재 여부 계산
        boolean hasNext = trendPage.size() > TREND_PAGE_SIZE;

        // 실제 화면에 보여줄 트렌드 계산
        List<TrendContent> trends = trendPage.subList(
                0,
                Math.min(trendPage.size(), TREND_PAGE_SIZE)
        );

        // trendId 추출
        List<Long> trendIds = trends.stream()
                .map(TrendContent::getId)
                .toList();

        // trendId 로 태그 조회
        Map<Long, List<String>> tagsByTrendId = findTagsByTrendIds(trendIds);

        // responseDTO 로 변환
        List<TrendResponse.TrendItem> items = trends.stream()
                .map(trend -> TrendResponse.TrendItem.toTrendItem(
                        trend,
                        tagsByTrendId.getOrDefault(trend.getId(), List.of())
                ))
                .toList();

        // 다음 cursor 계산
        Long nextCursor = hasNext && !trends.isEmpty()
                ? trends.get(trends.size() - 1).getId()
                : null;

        return TrendResponse.TrendList.toTrendList(items, nextCursor, hasNext, TREND_PAGE_SIZE);
    }

    // 트렌드 상세 조회
    @Transactional(readOnly = true)
    public TrendResponse.TrendDetail getTrendDetail(Long trendId) {

        // trendId 유효성 검사 및 조회
        TrendContent trend = trendContentRepository.findById(trendId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TREND_NOT_FOUND));

        // 트렌드-태그 조회
        List<String> tags = findTagsByTrendId(trendId);

        return TrendResponse.TrendDetail.toTrendDetail(trend, tags);
    }

    // cursor 기준 트렌드 조회
    private List<TrendContent> findTrendPage(Long cursor) {

        // 첫 요청일 때 목록 조회
        if (cursor == null) {
            return trendContentRepository.findAllByOrderByCreatedAtDescIdDesc(TREND_PAGE_REQUEST);
        }

        // cursor 유효성 확인 후 목록 조회
        TrendContent cursorTrend = trendContentRepository.findById(cursor)
                .orElseThrow(() -> new BusinessException(ErrorCode.TREND_NOT_FOUND));

        return trendContentRepository.findNextPage(
                cursorTrend.getCreatedAt(),
                cursorTrend.getId(),
                TREND_PAGE_REQUEST
        );
    }

    // 트렌드 id 로 태그 조회 (단건)
    private List<String> findTagsByTrendId(Long trendId) {
        return trendTagRepository.findAllByTrendIdOrderByIdAsc(trendId)
                .stream()
                .map(trendTag -> trendTag.getTag().getTagName())
                .toList();
    }

    // 트렌드 id 로 태그 조회 (목록, N+1 방지)
    private Map<Long, List<String>> findTagsByTrendIds(List<Long> trendIds) {

        if (trendIds.isEmpty()) {
            return Map.of();
        }

        return trendTagRepository.findAllByTrendIdInOrderByIdAsc(trendIds)
                .stream()
                .collect(Collectors.groupingBy(
                        trendTag -> trendTag.getTrend().getId(),
                        Collectors.mapping(
                                trendTag -> trendTag.getTag().getTagName(),
                                Collectors.toList()
                        )
                ));
    }
}

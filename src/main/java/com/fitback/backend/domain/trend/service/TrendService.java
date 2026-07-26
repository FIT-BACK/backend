package com.fitback.backend.domain.trend.service;

import com.fitback.backend.domain.closet.entity.ClosetTargetType;
import com.fitback.backend.domain.closet.repository.ClosetSaveRepository;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.trend.dto.TrendResponse;
import com.fitback.backend.domain.trend.entity.TrendContent;
import com.fitback.backend.domain.trend.repository.TrendContentRepository;
import com.fitback.backend.domain.trend.repository.TrendTagRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final ClosetSaveRepository closetSaveRepository;

    // 트렌드 목록 조회
    @Transactional(readOnly = true)
    public TrendResponse.TrendList getTrends(Long cursor, String tag, Member member) {

        // 입력 받은 태그의 앞 뒤 공백 제거
        String normalizedTag = normalizeTag(tag);

        // cursor, tag 기준 다음 페이지 분량의 트렌드 목록 조회
        List<TrendContent> trendPage = findTrendPage(cursor, normalizedTag);

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

        // 현재 로그인 한 유저가 클로젯에 저장한 트렌드 조회
        Set<Long> savedTrendIds = findSavedTrendIds(trendIds, member);

        // responseDTO 로 변환
        List<TrendResponse.TrendItem> items = trends.stream()
                .map(trend -> TrendResponse.TrendItem.toTrendItem(
                        trend,
                        tagsByTrendId.getOrDefault(trend.getId(), List.of()),
                        savedTrendIds.contains(trend.getId())
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
    public TrendResponse.TrendDetail getTrendDetail(Long trendId, Member member) {

        // trendId 유효성 검사 및 조회
        TrendContent trend = trendContentRepository.findById(trendId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TREND_NOT_FOUND));

        // 트렌드-태그 조회
        List<String> tags = findTagsByTrendId(trendId);

        // 로그인 상태면 해당 트렌드를 클로젯에 저장했는지 여부 계산
        boolean isSaved = member != null && closetSaveRepository.existsByMemberIdAndTargetTypeAndTargetId(
                member.getId(),
                ClosetTargetType.TREND,
                trendId
        );

        return TrendResponse.TrendDetail.toTrendDetail(trend, tags, isSaved);
    }

    // cursor, tag 기준 트렌드 조회
    private List<TrendContent> findTrendPage(Long cursor, String tag) {

        // 첫 요청일 때 목록 조회
        if (cursor == null) {
            return tag != null
                    ? trendContentRepository.findAllByTagName(tag, TREND_PAGE_REQUEST)
                    : trendContentRepository.findAllByOrderByCreatedAtDescIdDesc(TREND_PAGE_REQUEST);
        }

        // cursor 유효성 확인 후 목록 조회
        TrendContent cursorTrend = findCursorTrend(cursor, tag)
                .orElseThrow(() -> new BusinessException(ErrorCode.TREND_NOT_FOUND));

        return tag != null
                ? trendContentRepository.findNextPageByTagName(
                        tag, cursorTrend.getCreatedAt(), cursorTrend.getId(), TREND_PAGE_REQUEST)
                : trendContentRepository.findNextPage(
                        cursorTrend.getCreatedAt(), cursorTrend.getId(), TREND_PAGE_REQUEST);
    }

    private Optional<TrendContent> findCursorTrend(Long cursor, String tag) {
        if (tag != null) {
            return trendContentRepository.findCursorByIdAndTagName(cursor, tag);
        }
        return trendContentRepository.findById(cursor);
    }

    // 입력 받은 태그 공백 제거
    private String normalizeTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        return tag.trim();
    }

    // 현재 로그인 한 유저가 클로젯에 저장한 트렌드 조회
    private Set<Long> findSavedTrendIds(List<Long> trendIds, Member member) {
        if (member == null || trendIds.isEmpty()) {
            return Set.of();
        }
        return closetSaveRepository.findSavedTargetIds(member.getId(), ClosetTargetType.TREND, trendIds);
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

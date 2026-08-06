package com.fitback.backend.domain.trend.service;

import com.fitback.backend.domain.closet.entity.ClosetTargetType;
import com.fitback.backend.domain.closet.repository.ClosetSaveRepository;
import com.fitback.backend.domain.lookbook.dto.LookbookResponse;
import com.fitback.backend.domain.lookbook.service.LookbookService;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.trend.dto.TrendResponse;
import com.fitback.backend.domain.trend.entity.TrendContent;
import com.fitback.backend.domain.trend.repository.TrendContentRepository;
import com.fitback.backend.domain.trend.repository.TrendTagRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.List;
import java.util.Map;
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
    private static final Pageable SEARCH_PAGE_REQUEST = PageRequest.of(0, TREND_PAGE_SIZE);

    private final TrendContentRepository trendContentRepository;
    private final TrendTagRepository trendTagRepository;
    private final ClosetSaveRepository closetSaveRepository;
    private final LookbookService lookbookService;

    // 트렌드 목록 조회
    @Transactional(readOnly = true)
    public TrendResponse.TrendList getTrends(Long cursor, String tag, Member member) {

        // 입력 받은 태그의 앞 뒤 공백 제거
        String normalizedTag = normalizeTag(tag);

        // 인증 여부와 tag 조건에 맞는 다음 페이지 분량의 트렌드 목록 조회
        List<TrendContent> trendPage = findTrendPage(cursor, normalizedTag, member);

        // 다음 페이지 존재 여부 계산
        boolean hasNext = trendPage.size() > TREND_PAGE_SIZE;

        // 실제 화면에 보여줄 트렌드 계산
        List<TrendContent> trends = trendPage.subList(
                0,
                Math.min(trendPage.size(), TREND_PAGE_SIZE)
        );

        List<TrendResponse.TrendItem> items = toTrendItems(trends, member);

        // 다음 cursor 계산
        Long nextCursor = hasNext && !trends.isEmpty()
                ? trends.get(trends.size() - 1).getId()
                : null;

        return TrendResponse.TrendList.toTrendList(items, nextCursor, hasNext, TREND_PAGE_SIZE);
    }

    @Transactional(readOnly = true)
    public List<TrendResponse.TrendItem> searchTrends(String keyword, Member member) {
        List<TrendContent> trends = trendContentRepository.searchByKeyword(
                keyword,
                SEARCH_PAGE_REQUEST
        );
        return toTrendItems(trends, member);
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

    // 트렌드 관련 룩북 조회
    @Transactional(readOnly = true)
    public LookbookResponse.LookbookList getRelatedLookbooks(
            Long trendId,
            Long cursor,
            Member member
    ) {
        // 존재하는 트렌드만 관련 룩북 조회 허용
        if (!trendContentRepository.existsById(trendId)) {
            throw new BusinessException(ErrorCode.TREND_NOT_FOUND);
        }
        return lookbookService.getRelatedLookbooks(trendId, cursor, member);
    }

    // tag 필터를 우선 적용하고 로그인 회원은 관심 태그 기준으로 트렌드 조회
    private List<TrendContent> findTrendPage(Long cursor, String tag, Member member) {

        if (tag != null) {
            return findTagFilteredTrendPage(cursor, tag);
        }

        if (member != null) {
            return findPersonalizedTrendPage(cursor, member.getId());
        }

        return findLatestTrendPage(cursor);
    }

    // 비로그인 회원과 관심 태그 미설정 회원의 최신순 트렌드 조회
    private List<TrendContent> findLatestTrendPage(Long cursor) {

        // 첫 요청일 때 목록 조회
        if (cursor == null) {
            return trendContentRepository.findAllByOrderByCreatedAtDescIdDesc(TREND_PAGE_REQUEST);
        }

        TrendContent cursorTrend = trendContentRepository.findById(cursor)
                .orElseThrow(() -> new BusinessException(ErrorCode.TREND_NOT_FOUND));
        return trendContentRepository.findNextPage(
                cursorTrend.getCreatedAt(),
                cursorTrend.getId(),
                TREND_PAGE_REQUEST
        );
    }

    // 요청한 tag가 연결된 트렌드만 최신순 조회
    private List<TrendContent> findTagFilteredTrendPage(Long cursor, String tag) {
        if (cursor == null) {
            return trendContentRepository.findAllByTagName(tag, TREND_PAGE_REQUEST);
        }

        TrendContent cursorTrend = trendContentRepository.findCursorByIdAndTagName(cursor, tag)
                .orElseThrow(() -> new BusinessException(ErrorCode.TREND_NOT_FOUND));
        return trendContentRepository.findNextPageByTagName(
                tag,
                cursorTrend.getCreatedAt(),
                cursorTrend.getId(),
                TREND_PAGE_REQUEST
        );
    }

    // 관심 태그 일치 그룹을 우선하고 각 그룹 안에서는 최신순 조회
    private List<TrendContent> findPersonalizedTrendPage(Long cursor, Long memberId) {
        if (cursor == null) {
            return trendContentRepository.findAllPrioritizingMemberTags(
                    memberId,
                    TREND_PAGE_REQUEST
            );
        }

        TrendContent cursorTrend = trendContentRepository.findById(cursor)
                .orElseThrow(() -> new BusinessException(ErrorCode.TREND_NOT_FOUND));
        boolean cursorMatchesInterest = trendTagRepository.existsMemberInterestMatch(
                cursorTrend.getId(),
                memberId
        );

        return trendContentRepository.findNextPagePrioritizingMemberTags(
                memberId,
                cursorMatchesInterest,
                cursorTrend.getCreatedAt(),
                cursorTrend.getId(),
                TREND_PAGE_REQUEST
        );
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

    private List<TrendResponse.TrendItem> toTrendItems(
            List<TrendContent> trends,
            Member member
    ) {
        List<Long> trendIds = trends.stream()
                .map(TrendContent::getId)
                .toList();
        Map<Long, List<String>> tagsByTrendId = findTagsByTrendIds(trendIds);
        Set<Long> savedTrendIds = findSavedTrendIds(trendIds, member);
        return trends.stream()
                .map(trend -> TrendResponse.TrendItem.toTrendItem(
                        trend,
                        tagsByTrendId.getOrDefault(trend.getId(), List.of()),
                        savedTrendIds.contains(trend.getId())
                ))
                .toList();
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

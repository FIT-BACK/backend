package com.fitback.backend.domain.closet.service;

import com.fitback.backend.domain.analysis.service.AnalysisReportSaveService;
import com.fitback.backend.domain.closet.dto.ClosetSaveRequest;
import com.fitback.backend.domain.closet.dto.ClosetSaveResponse;
import com.fitback.backend.domain.closet.entity.ClosetSave;
import com.fitback.backend.domain.closet.entity.ClosetTargetType;
import com.fitback.backend.domain.closet.repository.ClosetSaveRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository;
import com.fitback.backend.domain.lookbook.service.LookbookService;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.trend.entity.TrendContent;
import com.fitback.backend.domain.trend.repository.TrendContentRepository;
import com.fitback.backend.domain.trend.repository.TrendTagRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ClosetSaveService {

    private static final int CLOSET_SAVE_PAGE_SIZE = 10;
    private static final Pageable CLOSET_SAVE_PAGE_REQUEST = PageRequest.of(0, CLOSET_SAVE_PAGE_SIZE + 1);

    private final ClosetSaveRepository closetSaveRepository;
    private final MemberRepository memberRepository;
    private final LookbookRepository lookbookRepository;
    private final TrendContentRepository trendContentRepository;
    private final TrendTagRepository trendTagRepository;
    private final LookbookService lookbookService;
    private final AnalysisReportSaveService analysisReportSaveService;

    // 마이 클로젯 저장
    @Transactional
    public ClosetSave save(Long memberId, ClosetSaveRequest.Create request) {

        // 저장 대상이 실제로 존재하는지 확인
        validateTargetExists(request.targetType(), request.targetId());

        // 이미 저장된 항목인지 확인
        boolean alreadySaved = closetSaveRepository.existsByMemberIdAndTargetTypeAndTargetId(
                memberId,
                request.targetType(),
                request.targetId()
        );
        if (alreadySaved) {
            throw new BusinessException(ErrorCode.CLOSET_ALREADY_SAVED);
        }

        // memberId로 회원 존재 여부 확인 후 저장
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        ClosetSave closetSave = ClosetSave.create(member, request.targetType(), request.targetId());

        return closetSaveRepository.save(closetSave);
    }

    // 저장 대상 유효성 검사
    private void validateTargetExists(ClosetTargetType targetType, Long targetId) {
        switch (targetType) {
            case TREND -> {
                if (!trendContentRepository.existsById(targetId)) {
                    throw new BusinessException(ErrorCode.TREND_NOT_FOUND);
                }
            }
            case LOOKBOOK -> lookbookRepository.findByIdAndDeletedAtIsNull(targetId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.LOOKBOOK_NOT_FOUND));
            case ANALYSIS_REPORT -> throw new BusinessException(ErrorCode.CLOSET_TARGET_UNSUPPORTED);
            default -> throw new BusinessException(ErrorCode.CLOSET_TARGET_UNSUPPORTED);
        }
    }

    // 마이 클로젯 저장 취소
    @Transactional
    public void cancel(Long memberId, Long saveId) {

        // saveId, memberId 로 본인 소유 확인
        ClosetSave closetSave = closetSaveRepository.findByIdAndMemberId(saveId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CLOSET_NOT_FOUND));

        closetSaveRepository.delete(closetSave);
    }

    // 마이 클로젯 목록 조회
    @Transactional(readOnly = true)
    public ClosetSaveResponse.ClosetSaveList getClosetSaves(Long memberId, ClosetTargetType targetType, Long cursor) {

        // cursor, targetType 기준 다음 페이지 분량의 저장 목록 조회
        List<ClosetSave> closetSavePage = findClosetSavePage(memberId, targetType, cursor);

        // 다음 페이지 존재 여부 계산
        boolean hasNext = closetSavePage.size() > CLOSET_SAVE_PAGE_SIZE;

        // 실제 화면에 보여줄 저장 목록 계산
        List<ClosetSave> closetSaves = closetSavePage.subList(
                0,
                Math.min(closetSavePage.size(), CLOSET_SAVE_PAGE_SIZE)
        );

        // responseDTO 로 변환
        List<ClosetSaveResponse.ClosetSaveItem> items = toClosetSaveItems(closetSaves, memberId);

        // 다음 cursor 계산
        Long nextCursor = hasNext && !closetSaves.isEmpty()
                ? closetSaves.get(closetSaves.size() - 1).getId()
                : null;

        return ClosetSaveResponse.ClosetSaveList.toClosetSaveList(items, nextCursor, hasNext, CLOSET_SAVE_PAGE_SIZE);
    }

    // 저장 대상 타입별 썸네일/태그 조립
    private List<ClosetSaveResponse.ClosetSaveItem> toClosetSaveItems(
            List<ClosetSave> closetSaves,
            Long memberId
    ) {
        List<Long> trendIds = targetIdsOf(closetSaves, ClosetTargetType.TREND);
        Map<Long, TrendContent> trendsById = findTrendsById(trendIds);
        Map<Long, List<String>> tagsByTrendId = findTagsByTrendIds(trendIds);
        Map<Long, LookbookService.ClosetLookbookView> lookbookViews = lookbookService.findClosetViews(
                targetIdsOf(closetSaves, ClosetTargetType.LOOKBOOK)
        );
        Map<Long, AnalysisReportSaveService.ClosetReportView> reportViews =
                analysisReportSaveService.findClosetViews(
                        targetIdsOf(closetSaves, ClosetTargetType.ANALYSIS_REPORT),
                        memberId
                );

        // 타입 추가 시 switch 가 컴파일 에러로 누락을 잡아줌
        return closetSaves.stream()
                .map(closetSave -> switch (closetSave.getTargetType()) {
                    case TREND -> toTrendItem(closetSave, trendsById, tagsByTrendId);
                    case LOOKBOOK -> toLookbookItem(closetSave, lookbookViews);
                    case ANALYSIS_REPORT -> toReportItem(closetSave, reportViews);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    // 타입별 대상 id 추출
    private List<Long> targetIdsOf(List<ClosetSave> closetSaves, ClosetTargetType targetType) {
        return closetSaves.stream()
                .filter(closetSave -> closetSave.getTargetType() == targetType)
                .map(ClosetSave::getTargetId)
                .toList();
    }

    // 대상이 삭제되었으면 null 반환해 목록에서 제외
    private ClosetSaveResponse.ClosetSaveItem toTrendItem(
            ClosetSave closetSave,
            Map<Long, TrendContent> trendsById,
            Map<Long, List<String>> tagsByTrendId
    ) {
        TrendContent trend = trendsById.get(closetSave.getTargetId());
        if (trend == null) {
            return null;
        }
        return ClosetSaveResponse.ClosetSaveItem.toClosetSaveItem(
                closetSave,
                trend.getImageUrl(),
                null,
                tagsByTrendId.getOrDefault(closetSave.getTargetId(), List.of())
        );
    }

    // 대상이 삭제되었으면 null 반환해 목록에서 제외
    private ClosetSaveResponse.ClosetSaveItem toLookbookItem(
            ClosetSave closetSave,
            Map<Long, LookbookService.ClosetLookbookView> lookbookViews
    ) {
        LookbookService.ClosetLookbookView view = lookbookViews.get(closetSave.getTargetId());
        if (view == null) {
            return null;
        }
        return ClosetSaveResponse.ClosetSaveItem.toClosetSaveItem(
                closetSave,
                view.thumbnailUrl(),
                view.matchedImageUrl(),
                view.tags()
        );
    }

    // 대상이 삭제되었으면 null 반환해 목록에서 제외
    private ClosetSaveResponse.ClosetSaveItem toReportItem(
            ClosetSave closetSave,
            Map<Long, AnalysisReportSaveService.ClosetReportView> reportViews
    ) {
        AnalysisReportSaveService.ClosetReportView view = reportViews.get(closetSave.getTargetId());
        if (view == null) {
            return null;
        }
        return ClosetSaveResponse.ClosetSaveItem.toClosetSaveItem(
                closetSave,
                view.thumbnailUrl(),
                null,
                view.tags()
        );
    }

    // cursor, targetType 기준 저장 목록 조회
    private List<ClosetSave> findClosetSavePage(Long memberId, ClosetTargetType targetType, Long cursor) {

        // 첫 요청일 때 목록 조회
        if (cursor == null) {
            return targetType == null
                    ? closetSaveRepository.findAllByMemberIdOrderByCreatedAtDescIdDesc(memberId, CLOSET_SAVE_PAGE_REQUEST)
                    : closetSaveRepository.findAllByMemberIdAndTargetTypeOrderByCreatedAtDescIdDesc(
                            memberId, targetType, CLOSET_SAVE_PAGE_REQUEST);
        }

        // cursor 유효성 확인 후 목록 조회
        ClosetSave cursorClosetSave = closetSaveRepository.findByIdAndMemberId(cursor, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CLOSET_NOT_FOUND));

        return targetType == null
                ? closetSaveRepository.findNextPage(
                        memberId, cursorClosetSave.getCreatedAt(), cursorClosetSave.getId(), CLOSET_SAVE_PAGE_REQUEST)
                : closetSaveRepository.findNextPageByTargetType(
                        memberId, targetType, cursorClosetSave.getCreatedAt(), cursorClosetSave.getId(),
                        CLOSET_SAVE_PAGE_REQUEST);
    }

    // trendId 로 트렌드 조회 (N+1 방지)
    private Map<Long, TrendContent> findTrendsById(List<Long> trendIds) {
        if (trendIds.isEmpty()) {
            return Map.of();
        }
        return trendContentRepository.findAllById(trendIds).stream()
                .collect(Collectors.toMap(TrendContent::getId, Function.identity()));
    }

    // trendId 로 태그 조회 (N+1 방지)
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

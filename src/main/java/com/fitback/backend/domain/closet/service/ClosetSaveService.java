package com.fitback.backend.domain.closet.service;

import com.fitback.backend.domain.closet.dto.ClosetSaveRequest;
import com.fitback.backend.domain.closet.dto.ClosetSaveResponse;
import com.fitback.backend.domain.closet.entity.ClosetSave;
import com.fitback.backend.domain.closet.entity.ClosetTargetType;
import com.fitback.backend.domain.closet.repository.ClosetSaveRepository;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.List;
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

    // 마이 클로젯 저장
    @Transactional
    public ClosetSave save(Long memberId, ClosetSaveRequest.Create request) {

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
        // TREND/LOOKBOOK/ANALYSIS_REPORT 모두 thumbnailUrl=null, tags=[] 로 반환
        // (TREND enrichment는 feature/#60-trend가 develop에 머지된 이후 별도 작업으로 추가 예정)
        List<ClosetSaveResponse.ClosetSaveItem> items = closetSaves.stream()
                .map(closetSave -> ClosetSaveResponse.ClosetSaveItem.toClosetSaveItem(closetSave, null, List.of()))
                .toList();

        // 다음 cursor 계산
        Long nextCursor = hasNext && !closetSaves.isEmpty()
                ? closetSaves.get(closetSaves.size() - 1).getId()
                : null;

        return ClosetSaveResponse.ClosetSaveList.toClosetSaveList(items, nextCursor, hasNext, CLOSET_SAVE_PAGE_SIZE);
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
}

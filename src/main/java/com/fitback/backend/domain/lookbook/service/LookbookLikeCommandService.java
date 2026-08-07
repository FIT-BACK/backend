package com.fitback.backend.domain.lookbook.service;

import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.lookbook.entity.LookbookLike;
import com.fitback.backend.domain.lookbook.repository.LookbookLikeRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.notification.event.LookbookLikedEvent;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LookbookLikeCommandService {

    private final LookbookRepository lookbookRepository;
    private final LookbookLikeRepository lookbookLikeRepository;
    private final ApplicationEventPublisher eventPublisher;

    // 룩북-좋아요 엔티티 생성
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Integer createLike(Long lookbookId, Member member) {

        // lookbookId 유효성 검사 및 조회
        Lookbook lookbook = lookbookRepository.findByIdAndDeletedAtIsNull(lookbookId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "룩북을 찾을 수 없습니다."
                ));

        // 룩북-좋아요 엔티티 생성 후 바로 쿼리를 날림 (동시성 이슈 처리)
        LookbookLike lookbookLike = LookbookLike.create(lookbook, member);
        lookbookLikeRepository.saveAndFlush(lookbookLike);

        // 룩북의 likeCount 1 증가.
        int updatedRows = lookbookRepository.incrementLikeCount(lookbookId);
        if (updatedRows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "룩북을 찾을 수 없습니다.");
        }

        // 룩북 작성자에게 보낼 좋아요 알림 발행 (실제 생성은 이 트랜잭션 커밋 이후)
        // 중복 좋아요는 위 saveAndFlush에서 예외로 빠지므로 이 지점까지 오지 않음
        eventPublisher.publishEvent(new LookbookLikedEvent(
                lookbookId,
                lookbook.getMember().getId(),
                member.getId(),
                member.getNickname()
        ));

        return lookbookRepository.findLikeCountByIdAndDeletedAtIsNull(lookbookId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "룩북을 찾을 수 없습니다."
                ));
    }

    // 룩북-좋아요 엔티티 삭제
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Integer deleteLike(Long lookbookId, Member member) {

        // 현재 회원의 룩북-좋아요 엔티티를 hard delete 방식으로 삭제
        int deletedRows = lookbookLikeRepository.deleteByLookbookIdAndMemberId(
                lookbookId,
                member.getId()
        );

        // 실제 삭제된 좋아요가 있을 때만 룩북의 likeCount 1 감소
        if (deletedRows > 0) {
            int updatedRows = lookbookRepository.decrementLikeCount(lookbookId);
            if (updatedRows == 0) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "룩북을 찾을 수 없습니다.");
            }
        }

        return lookbookRepository.findLikeCountByIdAndDeletedAtIsNull(lookbookId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "룩북을 찾을 수 없습니다."
                ));
    }
}

package com.fitback.backend.domain.lookbook.service;

import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.lookbook.entity.LookbookLike;
import com.fitback.backend.domain.lookbook.repository.LookbookLikeRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.notification.entity.MemberNotificationSetting;
import com.fitback.backend.domain.notification.entity.Notification;
import com.fitback.backend.domain.notification.entity.NotificationType;
import com.fitback.backend.domain.notification.repository.MemberNotificationSettingRepository;
import com.fitback.backend.domain.notification.repository.NotificationRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LookbookLikeCommandService {

    private static final String LOOKBOOK_LIKED_TITLE = "룩북에 좋아요가 달렸어요";

    private final LookbookRepository lookbookRepository;
    private final LookbookLikeRepository lookbookLikeRepository;
    private final NotificationRepository notificationRepository;
    private final MemberNotificationSettingRepository notificationSettingRepository;

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

        notifyLike(lookbook, member);

        return lookbookRepository.findLikeCountByIdAndDeletedAtIsNull(lookbookId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "룩북을 찾을 수 없습니다."
                ));
    }

    // 룩북 소유자에게 좋아요 알림 생성 (본인 좋아요이거나 알림을 꺼둔 경우 제외)
    private void notifyLike(Lookbook lookbook, Member liker) {
        Member owner = lookbook.getMember();
        if (owner.getId().equals(liker.getId())) {
            return;
        }
        if (!isLookbookLikedNotificationEnabled(owner)) {
            return;
        }

        Notification notification = Notification.create(
                owner,
                NotificationType.LOOKBOOK_LIKED,
                liker.getId(),
                lookbook.getId(),
                null,
                null,
                LOOKBOOK_LIKED_TITLE,
                liker.getNickname() + "님이 회원님의 룩북에 좋아요를 눌렀습니다."
        );
        notificationRepository.save(notification);
    }

    // 설정 row가 없으면 기본값(허용)으로 간주
    private boolean isLookbookLikedNotificationEnabled(Member owner) {
        return notificationSettingRepository.findById(owner.getId())
                .map(MemberNotificationSetting::getLookbookLikedEnabled)
                .orElse(true);
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

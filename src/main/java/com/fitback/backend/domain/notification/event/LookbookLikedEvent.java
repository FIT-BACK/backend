package com.fitback.backend.domain.notification.event;

//룩북 좋아요 발생
public record LookbookLikedEvent(
        Long lookbookId,
        Long recipientMemberId,
        Long actorMemberId,
        String actorNickname
) {
}

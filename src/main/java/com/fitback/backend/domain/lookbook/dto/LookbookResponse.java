package com.fitback.backend.domain.lookbook.dto;

import com.fitback.backend.domain.lookbook.entity.Lookbook;
import java.util.List;
import lombok.Builder;

// 룩북 응답 DTO
public final class LookbookResponse {

    private LookbookResponse() {
    }

    // 룩북 업로드
    @Builder
    public record LookbookCreate(
            Long lookbookId,
            Long memberId,
            String originalImageUrl,
            String matchedImageUrl,
            List<Long> tagIds,
            String purchaseUrl,
            String comment,
            Integer likeCount
    ) {

        public static LookbookCreate toLookbookCreate(Lookbook lookbook, List<Long> tagIds) {
            return LookbookCreate.builder()
                    .lookbookId(lookbook.getId())
                    .memberId(lookbook.getMember().getId())
                    .originalImageUrl(lookbook.getOriginalImageUrl())
                    .matchedImageUrl(lookbook.getMatchedImageUrl())
                    .tagIds(List.copyOf(tagIds))
                    .purchaseUrl(lookbook.getPurchaseUrl())
                    .comment(lookbook.getComment())
                    .likeCount(lookbook.getLikeCount())
                    .build();
        }
    }
}

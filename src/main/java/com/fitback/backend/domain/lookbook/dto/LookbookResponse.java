package com.fitback.backend.domain.lookbook.dto;

import com.fitback.backend.domain.lookbook.entity.Lookbook;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

// 룩북 응답 DTO
public final class LookbookResponse {

    private LookbookResponse() {
    }

    // 룩북 업로드
    @Schema(name = "LookbookCreateResponse")
    @Builder
    public record LookbookCreate(
            Long lookbookId
    ) {

        public static LookbookCreate toLookbookCreate(Lookbook lookbook) {
            return LookbookCreate.builder()
                    .lookbookId(lookbook.getId())
                    .build();
        }
    }

    // 룩북 목록 조회
    @Builder
    public record LookbookList(
            List<LookbookItem> items,
            Long nextCursor,
            boolean hasNext,
            Integer pageSize
    ) {

        public static LookbookList toLookbookList(
                List<LookbookItem> items,
                Long nextCursor,
                boolean hasNext,
                Integer pageSize
        ) {
            return LookbookList.builder()
                    .items(List.copyOf(items))
                    .nextCursor(nextCursor)
                    .hasNext(hasNext)
                    .pageSize(pageSize)
                    .build();
        }
    }

    // 룩북 목록 조회에서 사용할 룩북 정보
    @Builder
    public record LookbookItem(
            Long lookbookId,
            String originalImageUrl,
            String matchedImageUrl,
            String authorNickname,
            String authorProfileImageUrl,
            List<String> tags,
            Integer likeCount,
            boolean isLiked
    ) {

        public static LookbookItem toLookbookItem(
                Lookbook lookbook,
                List<String> tags,
                boolean isLiked
        ) {
            return LookbookItem.builder()
                    .lookbookId(lookbook.getId())
                    .originalImageUrl(lookbook.getOriginalImageUrl())
                    .matchedImageUrl(lookbook.getMatchedImageUrl())
                    .authorNickname(lookbook.getMember().getNickname())
                    .authorProfileImageUrl(lookbook.getMember().getProfileImageUrl())
                    .tags(List.copyOf(tags))
                    .likeCount(lookbook.getLikeCount())
                    .isLiked(isLiked)
                    .build();
        }
    }

    // 룩북 상세 조회
    @Builder
    public record LookbookDetail(
            String originalImageUrl,
            String matchedImageUrl,
            String authorNickname,
            String purchaseUrl,
            List<String> tags,
            Integer likeCount,
            boolean isLiked
    ) {

        public static LookbookDetail toLookbookDetail(
                Lookbook lookbook,
                List<String> tags,
                boolean isLiked
        ) {
            return LookbookDetail.builder()
                    .originalImageUrl(lookbook.getOriginalImageUrl())
                    .matchedImageUrl(lookbook.getMatchedImageUrl())
                    .authorNickname(lookbook.getMember().getNickname())
                    .purchaseUrl(lookbook.getPurchaseUrl())
                    .tags(List.copyOf(tags))
                    .likeCount(lookbook.getLikeCount())
                    .isLiked(isLiked)
                    .build();
        }
    }

    // 룩북 좋아요
    @Builder
    public record LookbookLike(
            Integer likeCount
    ) {

        public static LookbookLike toLookbookLike(Integer likeCount) {
            return LookbookLike.builder()
                    .likeCount(likeCount)
                    .build();
        }
    }

}

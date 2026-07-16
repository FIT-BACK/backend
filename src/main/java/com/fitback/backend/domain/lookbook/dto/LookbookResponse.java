package com.fitback.backend.domain.lookbook.dto;

import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import java.time.LocalDateTime;
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

    // 룩북 상세 조회
    @Builder
    public record LookbookDetail(
            Long lookbookId,
            Long memberId,
            String nickname,
            String profileImageUrl,
            String originalImageUrl,
            String matchedImageUrl,
            List<TagInfo> tags,
            String purchaseUrl,
            String comment,
            Integer likeCount,
            boolean likedByMe,
            LocalDateTime createdAt
    ) {

        public static LookbookDetail toLookbookDetail(
                Lookbook lookbook,
                List<TagInfo> tags,
                boolean likedByMe
        ) {
            return LookbookDetail.builder()
                    .lookbookId(lookbook.getId())
                    .memberId(lookbook.getMember().getId())
                    .nickname(lookbook.getMember().getNickname())
                    .profileImageUrl(lookbook.getMember().getProfileImageUrl())
                    .originalImageUrl(lookbook.getOriginalImageUrl())
                    .matchedImageUrl(lookbook.getMatchedImageUrl())
                    .tags(List.copyOf(tags))
                    .purchaseUrl(lookbook.getPurchaseUrl())
                    .comment(lookbook.getComment())
                    .likeCount(lookbook.getLikeCount())
                    .likedByMe(likedByMe)
                    .createdAt(lookbook.getCreatedAt())
                    .build();
        }
    }

    // 룩북-태그 용 dto
    @Builder
    public record TagInfo(
            Long tagId,
            String tagName,
            TagType tagType
    ) {

        public static TagInfo toTagInfo(Tag tag) {
            return TagInfo.builder()
                    .tagId(tag.getId())
                    .tagName(tag.getTagName())
                    .tagType(tag.getTagType())
                    .build();
        }
    }
}

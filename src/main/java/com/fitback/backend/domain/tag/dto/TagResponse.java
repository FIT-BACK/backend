package com.fitback.backend.domain.tag.dto;

import com.fitback.backend.domain.tag.entity.Tag;
import java.util.List;
import lombok.Builder;

// 태그 응답 DTO
public final class TagResponse {

    private TagResponse() {
    }

    // 태그 목록 조회
    @Builder
    public record TagList(List<TagItem> items) {

        public static TagList toTagList(List<TagItem> items) {
            return TagList.builder()
                    .items(List.copyOf(items))
                    .build();
        }
    }

    // 태그 목록 조회에서 사용할 태그 정보
    @Builder
    public record TagItem(Long tagId, String tagName) {

        public static TagItem toTagItem(Tag tag) {
            return TagItem.builder()
                    .tagId(tag.getId())
                    .tagName(tag.getTagName())
                    .build();
        }
    }
}

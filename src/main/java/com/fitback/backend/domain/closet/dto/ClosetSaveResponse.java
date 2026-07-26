package com.fitback.backend.domain.closet.dto;

import com.fitback.backend.domain.closet.entity.ClosetSave;
import com.fitback.backend.domain.closet.entity.ClosetTargetType;
import java.util.List;
import lombok.Builder;

// 마이 클로젯 응답 DTO
public final class ClosetSaveResponse {

    private ClosetSaveResponse() {
    }

    // 마이 클로젯 저장
    @Builder
    public record Created(
            Long saveId,
            ClosetTargetType targetType,
            Long targetId
    ) {

        public static Created from(ClosetSave closetSave) {
            return Created.builder()
                    .saveId(closetSave.getId())
                    .targetType(closetSave.getTargetType())
                    .targetId(closetSave.getTargetId())
                    .build();
        }
    }

    // 마이 클로젯 목록 조회
    @Builder
    public record ClosetSaveList(
            List<ClosetSaveItem> items,
            Long nextCursor,
            boolean hasNext,
            int pageSize
    ) {

        public static ClosetSaveList toClosetSaveList(
                List<ClosetSaveItem> items,
                Long nextCursor,
                boolean hasNext,
                int pageSize
        ) {
            return ClosetSaveList.builder()
                    .items(List.copyOf(items))
                    .nextCursor(nextCursor)
                    .hasNext(hasNext)
                    .pageSize(pageSize)
                    .build();
        }
    }

    // 마이 클로젯 목록 조회에서 사용할 저장 항목 정보
    @Builder
    public record ClosetSaveItem(
            Long saveId,
            ClosetTargetType targetType,
            Long targetId,
            String thumbnailUrl,
            List<String> tags
    ) {

        public static ClosetSaveItem toClosetSaveItem(
                ClosetSave closetSave,
                String thumbnailUrl,
                List<String> tags
        ) {
            return ClosetSaveItem.builder()
                    .saveId(closetSave.getId())
                    .targetType(closetSave.getTargetType())
                    .targetId(closetSave.getTargetId())
                    .thumbnailUrl(thumbnailUrl)
                    .tags(List.copyOf(tags))
                    .build();
        }
    }
}

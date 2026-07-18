package com.fitback.backend.domain.trend.dto;

import com.fitback.backend.domain.trend.entity.TrendContent;
import java.util.List;
import lombok.Builder;

// 트렌드 응답 DTO
public final class TrendResponse {

    private TrendResponse() {
    }

    // 트렌드 목록 조회
    @Builder
    public record TrendList(
            List<TrendItem> items,
            Long nextCursor,
            boolean hasNext,
            int pageSize
    ) {

        public static TrendList toTrendList(
                List<TrendItem> items,
                Long nextCursor,
                boolean hasNext,
                int pageSize
        ) {
            return TrendList.builder()
                    .items(List.copyOf(items))
                    .nextCursor(nextCursor)
                    .hasNext(hasNext)
                    .pageSize(pageSize)
                    .build();
        }
    }

    // 트렌드 목록 조회에서 사용할 트렌드 정보
    @Builder
    public record TrendItem(
            Long trendId,
            String title,
            String imageUrl,
            List<String> tags
    ) {

        public static TrendItem toTrendItem(TrendContent trend, List<String> tags) {
            return TrendItem.builder()
                    .trendId(trend.getId())
                    .title(trend.getTitle())
                    .imageUrl(trend.getImageUrl())
                    .tags(List.copyOf(tags))
                    .build();
        }
    }

    // 트렌드 상세 조회
    @Builder
    public record TrendDetail(
            String title,
            String imageUrl,
            String description,
            List<String> tags
    ) {

        public static TrendDetail toTrendDetail(TrendContent trend, List<String> tags) {
            return TrendDetail.builder()
                    .title(trend.getTitle())
                    .imageUrl(trend.getImageUrl())
                    .description(trend.getDescription())
                    .tags(List.copyOf(tags))
                    .build();
        }
    }
}

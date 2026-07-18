package com.fitback.backend.domain.trend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.trend.dto.TrendResponse;
import com.fitback.backend.domain.trend.service.TrendService;
import com.fitback.backend.global.response.ApiResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TrendControllerTest {

    @Mock
    private TrendService trendService;

    @InjectMocks
    private TrendController trendController;

    @Test
    void getTrendsReturnsSuccessResponse() {
        TrendResponse.TrendItem item = TrendResponse.TrendItem.builder()
                .trendId(1L)
                .title("스트릿 무드")
                .imageUrl("https://cdn.fitback.app/trends/1.jpg")
                .tags(List.of("스트릿"))
                .build();
        TrendResponse.TrendList serviceResponse = TrendResponse.TrendList.builder()
                .items(List.of(item))
                .nextCursor(null)
                .hasNext(false)
                .pageSize(10)
                .build();
        when(trendService.getTrends(null)).thenReturn(serviceResponse);

        ApiResponse<TrendResponse.TrendList> response = trendController.getTrends(null);

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("COMMON200_1");
        assertThat(response.data()).isEqualTo(serviceResponse);
        assertThat(response.data().items()).containsExactly(item);
        verify(trendService).getTrends(null);
    }

    @Test
    void getTrendsPassesCursorToService() {
        TrendResponse.TrendList serviceResponse = TrendResponse.TrendList.builder()
                .items(List.of())
                .nextCursor(null)
                .hasNext(false)
                .pageSize(10)
                .build();
        when(trendService.getTrends(2L)).thenReturn(serviceResponse);

        trendController.getTrends(2L);

        verify(trendService).getTrends(2L);
    }

    @Test
    void getTrendDetailReturnsSuccessResponse() {
        TrendResponse.TrendDetail serviceResponse = TrendResponse.TrendDetail.builder()
                .title("미니멀룩")
                .imageUrl("https://exampletrends.jpg")
                .description("무채색 아이템을 중심으로 구성한 미니멀 코디 모음")
                .tags(List.of("미니멀", "무채색"))
                .build();
        when(trendService.getTrendDetail(1L)).thenReturn(serviceResponse);

        ApiResponse<TrendResponse.TrendDetail> response = trendController.getTrendDetail(1L);

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("COMMON200_1");
        assertThat(response.data()).isEqualTo(serviceResponse);
        verify(trendService).getTrendDetail(1L);
    }
}

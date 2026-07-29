package com.fitback.backend.domain.contentsearch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.contentsearch.dto.ContentSearchResponse;
import com.fitback.backend.domain.lookbook.dto.LookbookResponse;
import com.fitback.backend.domain.lookbook.service.LookbookService;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.trend.dto.TrendResponse;
import com.fitback.backend.domain.trend.service.TrendService;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContentSearchServiceTest {

    private final TrendService trendService = mock(TrendService.class);
    private final LookbookService lookbookService = mock(LookbookService.class);
    private final ContentSearchService contentSearchService = new ContentSearchService(
            trendService,
            lookbookService
    );

    @Test
    void normalizesKeywordAndCombinesBothContentTypes() {
        Member member = mock(Member.class);
        TrendResponse.TrendItem trend = TrendResponse.TrendItem.builder()
                .trendId(1L)
                .title("Minimal Trend")
                .tags(List.of())
                .build();
        LookbookResponse.LookbookItem lookbook = LookbookResponse.LookbookItem.builder()
                .lookbookId(2L)
                .tags(List.of())
                .build();
        when(trendService.searchTrends("minimal", member)).thenReturn(List.of(trend));
        when(lookbookService.searchLookbooks("minimal", member))
                .thenReturn(List.of(lookbook));

        ContentSearchResponse response = contentSearchService.search(
                "  MiNiMaL  ",
                member
        );

        assertThat(response.trends()).containsExactly(trend);
        assertThat(response.lookbooks()).containsExactly(lookbook);
        verify(trendService).searchTrends("minimal", member);
        verify(lookbookService).searchLookbooks("minimal", member);
    }

    @Test
    void rejectsBlankKeywordBeforeSearching() {
        assertThatThrownBy(() -> contentSearchService.search("   ", null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VALIDATION_ERROR)
                );
        verifyNoInteractions(trendService, lookbookService);
    }

    @Test
    void rejectsKeywordLongerThanOneHundredCharacters() {
        assertThatThrownBy(() -> contentSearchService.search("a".repeat(101), null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VALIDATION_ERROR)
                );
        verifyNoInteractions(trendService, lookbookService);
    }
}

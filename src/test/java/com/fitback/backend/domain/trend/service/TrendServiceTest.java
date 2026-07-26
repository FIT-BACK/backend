package com.fitback.backend.domain.trend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.closet.repository.ClosetSaveRepository;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.domain.trend.dto.TrendResponse;
import com.fitback.backend.domain.trend.entity.TrendContent;
import com.fitback.backend.domain.trend.entity.TrendTag;
import com.fitback.backend.domain.trend.repository.TrendContentRepository;
import com.fitback.backend.domain.trend.repository.TrendTagRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TrendServiceTest {

    @Mock
    private TrendContentRepository trendContentRepository;

    @Mock
    private TrendTagRepository trendTagRepository;

    @Mock
    private ClosetSaveRepository closetSaveRepository;

    @InjectMocks
    private TrendService trendService;

    private Member member;
    private Tag minimalTag;
    private Tag streetTag;

    @BeforeEach
    void setUp() {
        member = Member.create("member@fitback.com", "fitback", "password", LoginProvider.EMAIL);
        ReflectionTestUtils.setField(member, "id", 1L);

        minimalTag = Tag.create("미니멀", TagType.DETAIL);
        ReflectionTestUtils.setField(minimalTag, "id", 10L);
        streetTag = Tag.create("스트릿", TagType.DETAIL);
        ReflectionTestUtils.setField(streetTag, "id", 20L);
    }

    @Test
    void getTrendDetailReturnsContentAndTags() {
        TrendContent trend = createTrend(100L, LocalDateTime.of(2026, 7, 16, 12, 0));
        when(trendContentRepository.findById(100L)).thenReturn(Optional.of(trend));
        when(trendTagRepository.findAllByTrendIdOrderByIdAsc(100L))
                .thenReturn(List.of(
                        TrendTag.create(trend, minimalTag),
                        TrendTag.create(trend, streetTag)
                ));

        TrendResponse.TrendDetail response = trendService.getTrendDetail(100L, null);

        assertThat(response.title()).isEqualTo("미니멀룩");
        assertThat(response.imageUrl()).isEqualTo("https://cdn.fitback.app/trends/100.jpg");
        assertThat(response.description()).isEqualTo("무채색 아이템을 중심으로 구성한 미니멀 코디 모음");
        assertThat(response.tags()).containsExactly("미니멀", "스트릿");
    }

    @Test
    void getTrendDetailReturnsEmptyTagsWhenNoTagsAssigned() {
        TrendContent trend = createTrend(100L, LocalDateTime.of(2026, 7, 16, 12, 0));
        when(trendContentRepository.findById(100L)).thenReturn(Optional.of(trend));
        when(trendTagRepository.findAllByTrendIdOrderByIdAsc(100L)).thenReturn(List.of());

        TrendResponse.TrendDetail response = trendService.getTrendDetail(100L, null);

        assertThat(response.tags()).isEmpty();
    }

    @Test
    void getTrendDetailFailsWhenTrendDoesNotExist() {
        when(trendContentRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> trendService.getTrendDetail(999L, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TREND_NOT_FOUND)
                );
    }

    @Test
    void getTrendsReturnsTenItemsAndNextCursor() {
        LocalDateTime latestCreatedAt = LocalDateTime.of(2026, 7, 16, 12, 0);
        List<TrendContent> trendPage = IntStream.range(0, 11)
                .mapToObj(index -> createTrend(
                        100L - index,
                        latestCreatedAt.minusMinutes(index)
                ))
                .toList();
        List<Long> returnedTrendIds = trendPage.subList(0, 10)
                .stream()
                .map(TrendContent::getId)
                .toList();
        when(trendContentRepository.findAllByOrderByCreatedAtDescIdDesc(any(Pageable.class)))
                .thenReturn(trendPage);
        when(trendTagRepository.findAllByTrendIdInOrderByIdAsc(returnedTrendIds))
                .thenReturn(List.of(TrendTag.create(trendPage.get(0), minimalTag)));

        TrendResponse.TrendList response = trendService.getTrends(null, null, null);

        assertThat(response.items()).hasSize(10);
        assertThat(response.items().get(0).trendId()).isEqualTo(100L);
        assertThat(response.items().get(0).tags()).containsExactly("미니멀");
        assertThat(response.items().get(1).tags()).isEmpty();
        assertThat(response.nextCursor()).isEqualTo(91L);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.pageSize()).isEqualTo(10);
    }

    @Test
    void getTrendsReturnsNoNextCursorWhenLastPage() {
        TrendContent trend = createTrend(100L, LocalDateTime.of(2026, 7, 16, 12, 0));
        when(trendContentRepository.findAllByOrderByCreatedAtDescIdDesc(any(Pageable.class)))
                .thenReturn(List.of(trend));
        when(trendTagRepository.findAllByTrendIdInOrderByIdAsc(List.of(100L)))
                .thenReturn(List.of());

        TrendResponse.TrendList response = trendService.getTrends(null, null, null);

        assertThat(response.items()).hasSize(1);
        assertThat(response.nextCursor()).isNull();
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    void getTrendsUsesCursorCreatedAtAndIdForNextPage() {
        LocalDateTime cursorCreatedAt = LocalDateTime.of(2026, 7, 16, 12, 0);
        TrendContent cursorTrend = createTrend(100L, cursorCreatedAt);
        TrendContent nextTrend = createTrend(99L, cursorCreatedAt.minusMinutes(1));
        when(trendContentRepository.findById(100L)).thenReturn(Optional.of(cursorTrend));
        when(trendContentRepository.findNextPage(
                eq(cursorCreatedAt),
                eq(100L),
                any(Pageable.class)
        )).thenReturn(List.of(nextTrend));
        when(trendTagRepository.findAllByTrendIdInOrderByIdAsc(List.of(99L)))
                .thenReturn(List.of());

        TrendResponse.TrendList response = trendService.getTrends(100L, null, null);

        assertThat(response.items())
                .extracting(TrendResponse.TrendItem::trendId)
                .containsExactly(99L);
    }

    @Test
    void getTrendsFailsWhenCursorDoesNotExist() {
        when(trendContentRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> trendService.getTrends(999L, null, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TREND_NOT_FOUND)
                );
    }

    private TrendContent createTrend(Long trendId, LocalDateTime createdAt) {
        TrendContent trend = TrendContent.create(
                "미니멀룩",
                "https://cdn.fitback.app/trends/" + trendId + ".jpg",
                "무채색 아이템을 중심으로 구성한 미니멀 코디 모음",
                member
        );
        ReflectionTestUtils.setField(trend, "id", trendId);
        ReflectionTestUtils.setField(trend, "createdAt", createdAt);
        return trend;
    }
}

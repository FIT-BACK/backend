package com.fitback.backend.domain.closet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.closet.dto.ClosetSaveRequest;
import com.fitback.backend.domain.closet.dto.ClosetSaveResponse;
import com.fitback.backend.domain.closet.entity.ClosetSave;
import com.fitback.backend.domain.closet.entity.ClosetTargetType;
import com.fitback.backend.domain.closet.repository.ClosetSaveRepository;
import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
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
class ClosetSaveServiceTest {

    @Mock
    private ClosetSaveRepository closetSaveRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private LookbookRepository lookbookRepository;

    @Mock
    private TrendContentRepository trendContentRepository;

    @Mock
    private TrendTagRepository trendTagRepository;

    @InjectMocks
    private ClosetSaveService closetSaveService;

    private Member member;

    @BeforeEach
    void setUp() {
        member = Member.create("member@fitback.com", "fitback", "password", LoginProvider.EMAIL);
        ReflectionTestUtils.setField(member, "id", 1L);
    }

    @Test
    void saveCreatesClosetSaveWhenNotAlreadySaved() {
        when(trendContentRepository.existsById(12L)).thenReturn(true);
        when(closetSaveRepository.existsByMemberIdAndTargetTypeAndTargetId(1L, ClosetTargetType.TREND, 12L))
                .thenReturn(false);
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(closetSaveRepository.save(any(ClosetSave.class))).thenAnswer(invocation -> {
            ClosetSave closetSave = invocation.getArgument(0);
            ReflectionTestUtils.setField(closetSave, "id", 100L);
            return closetSave;
        });

        ClosetSave result = closetSaveService.save(1L, new ClosetSaveRequest.Create(ClosetTargetType.TREND, 12L));

        assertThat(result.getId()).isEqualTo(100L);
        assertThat(result.getTargetType()).isEqualTo(ClosetTargetType.TREND);
        assertThat(result.getTargetId()).isEqualTo(12L);
        verify(closetSaveRepository).save(any(ClosetSave.class));
    }

    @Test
    void saveFailsWhenAlreadySaved() {
        when(trendContentRepository.existsById(1L)).thenReturn(true);
        when(closetSaveRepository.existsByMemberIdAndTargetTypeAndTargetId(1L, ClosetTargetType.TREND, 1L))
                .thenReturn(true);

        assertThatThrownBy(() -> closetSaveService.save(1L, new ClosetSaveRequest.Create(ClosetTargetType.TREND, 1L)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CLOSET_ALREADY_SAVED)
                );
        verify(closetSaveRepository, never()).save(any());
        verify(memberRepository, never()).findById(any());
    }

    @Test
    void saveFailsWhenTrendTargetDoesNotExist() {
        when(trendContentRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() ->
                closetSaveService.save(1L, new ClosetSaveRequest.Create(ClosetTargetType.TREND, 999L)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TREND_NOT_FOUND)
                );
        verify(closetSaveRepository, never())
                .existsByMemberIdAndTargetTypeAndTargetId(any(), any(), any());
        verify(closetSaveRepository, never()).save(any());
    }

    @Test
    void saveFailsWhenMemberDoesNotExist() {
        when(trendContentRepository.existsById(12L)).thenReturn(true);
        when(closetSaveRepository.existsByMemberIdAndTargetTypeAndTargetId(999L, ClosetTargetType.TREND, 12L))
                .thenReturn(false);
        when(memberRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                closetSaveService.save(999L, new ClosetSaveRequest.Create(ClosetTargetType.TREND, 12L)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND)
                );
        verify(closetSaveRepository, never()).save(any());
    }

    @Test
    void saveFailsWhenAnalysisReportUsesGenericClosetApi() {
        assertThatThrownBy(() -> closetSaveService.save(
                1L,
                new ClosetSaveRequest.Create(ClosetTargetType.ANALYSIS_REPORT, 12L)
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CLOSET_TARGET_UNSUPPORTED)
        );

        verify(closetSaveRepository, never())
                .existsByMemberIdAndTargetTypeAndTargetId(any(), any(), any());
        verify(closetSaveRepository, never()).save(any());
    }

    @Test
    void saveAllowsExistingLookbook() {
        when(lookbookRepository.findByIdAndDeletedAtIsNull(12L))
                .thenReturn(Optional.of(mock(Lookbook.class)));
        when(closetSaveRepository.existsByMemberIdAndTargetTypeAndTargetId(
                1L,
                ClosetTargetType.LOOKBOOK,
                12L
        )).thenReturn(false);
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(closetSaveRepository.save(any(ClosetSave.class))).thenAnswer(invocation -> {
            ClosetSave closetSave = invocation.getArgument(0);
            ReflectionTestUtils.setField(closetSave, "id", 101L);
            return closetSave;
        });

        ClosetSave result = closetSaveService.save(
                1L,
                new ClosetSaveRequest.Create(ClosetTargetType.LOOKBOOK, 12L)
        );

        assertThat(result.getId()).isEqualTo(101L);
        assertThat(result.getTargetType()).isEqualTo(ClosetTargetType.LOOKBOOK);
        assertThat(result.getTargetId()).isEqualTo(12L);
    }

    @Test
    void saveFailsWhenLookbookTargetDoesNotExist() {
        when(lookbookRepository.findByIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> closetSaveService.save(
                1L,
                new ClosetSaveRequest.Create(ClosetTargetType.LOOKBOOK, 999L)
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.LOOKBOOK_NOT_FOUND)
        );

        verify(closetSaveRepository, never())
                .existsByMemberIdAndTargetTypeAndTargetId(any(), any(), any());
        verify(closetSaveRepository, never()).save(any());
    }

    @Test
    void cancelDeletesClosetSaveWhenOwnedByMember() {
        ClosetSave closetSave = createClosetSave(10L, ClosetTargetType.TREND, 1L, LocalDateTime.now());
        when(closetSaveRepository.findByIdAndMemberId(10L, 1L)).thenReturn(Optional.of(closetSave));

        closetSaveService.cancel(1L, 10L);

        verify(closetSaveRepository).delete(closetSave);
    }

    @Test
    void cancelFailsWhenNotFoundOrNotOwned() {
        when(closetSaveRepository.findByIdAndMemberId(999L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> closetSaveService.cancel(1L, 999L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CLOSET_NOT_FOUND)
                );
        verify(closetSaveRepository, never()).delete(any(ClosetSave.class));
    }

    @Test
    void getClosetSavesReturnsTenItemsAndNextCursorWithoutFilter() {
        LocalDateTime latestCreatedAt = LocalDateTime.of(2026, 7, 19, 12, 0);
        List<ClosetSave> page = IntStream.range(0, 11)
                .mapToObj(index -> createClosetSave(
                        100L - index, ClosetTargetType.LOOKBOOK, 1L, latestCreatedAt.minusMinutes(index)))
                .toList();
        when(closetSaveRepository.findAllByMemberIdOrderByCreatedAtDescIdDesc(eq(1L), any(Pageable.class)))
                .thenReturn(page);

        ClosetSaveResponse.ClosetSaveList response = closetSaveService.getClosetSaves(1L, null, null);

        assertThat(response.items()).hasSize(10);
        assertThat(response.items().get(0).saveId()).isEqualTo(100L);
        assertThat(response.items().get(0).thumbnailUrl()).isNull();
        assertThat(response.items().get(0).tags()).isEmpty();
        assertThat(response.nextCursor()).isEqualTo(91L);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.pageSize()).isEqualTo(10);
    }

    @Test
    void getClosetSavesReturnsNoNextCursorWhenLastPage() {
        ClosetSave closetSave = createClosetSave(100L, ClosetTargetType.ANALYSIS_REPORT, 1L, LocalDateTime.now());
        when(closetSaveRepository.findAllByMemberIdOrderByCreatedAtDescIdDesc(eq(1L), any(Pageable.class)))
                .thenReturn(List.of(closetSave));

        ClosetSaveResponse.ClosetSaveList response = closetSaveService.getClosetSaves(1L, null, null);

        assertThat(response.items()).hasSize(1);
        assertThat(response.nextCursor()).isNull();
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    void getClosetSavesUsesTargetTypeFilterMethodWhenTargetTypeGiven() {
        ClosetSave closetSave = createClosetSave(100L, ClosetTargetType.TREND, 1L, LocalDateTime.now());
        when(closetSaveRepository.findAllByMemberIdAndTargetTypeOrderByCreatedAtDescIdDesc(
                eq(1L), eq(ClosetTargetType.TREND), any(Pageable.class)))
                .thenReturn(List.of(closetSave));

        ClosetSaveResponse.ClosetSaveList response = closetSaveService.getClosetSaves(1L, ClosetTargetType.TREND, null);

        assertThat(response.items()).extracting(ClosetSaveResponse.ClosetSaveItem::targetType)
                .containsExactly(ClosetTargetType.TREND);
        verify(closetSaveRepository, never())
                .findAllByMemberIdOrderByCreatedAtDescIdDesc(any(), any());
    }

    @Test
    void getClosetSavesEnrichesTrendItemsWithThumbnailAndTags() {
        ClosetSave closetSave = createClosetSave(100L, ClosetTargetType.TREND, 1L, LocalDateTime.now());
        TrendContent trend = TrendContent.create(
                "미니멀룩",
                "https://cdn.fitback.app/trends/1.jpg",
                "설명",
                member
        );
        ReflectionTestUtils.setField(trend, "id", 1L);
        Tag minimalTag = Tag.create("미니멀", TagType.DETAIL);
        ReflectionTestUtils.setField(minimalTag, "id", 10L);
        when(closetSaveRepository.findAllByMemberIdAndTargetTypeOrderByCreatedAtDescIdDesc(
                eq(1L), eq(ClosetTargetType.TREND), any(Pageable.class)))
                .thenReturn(List.of(closetSave));
        when(trendContentRepository.findAllById(List.of(1L))).thenReturn(List.of(trend));
        when(trendTagRepository.findAllByTrendIdInOrderByIdAsc(List.of(1L)))
                .thenReturn(List.of(TrendTag.create(trend, minimalTag)));

        ClosetSaveResponse.ClosetSaveList response =
                closetSaveService.getClosetSaves(1L, ClosetTargetType.TREND, null);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).thumbnailUrl()).isEqualTo("https://cdn.fitback.app/trends/1.jpg");
        assertThat(response.items().get(0).tags()).containsExactly("미니멀");
    }

    @Test
    void getClosetSavesLeavesNonTrendItemsUnenriched() {
        ClosetSave closetSave = createClosetSave(100L, ClosetTargetType.LOOKBOOK, 12L, LocalDateTime.now());
        when(closetSaveRepository.findAllByMemberIdOrderByCreatedAtDescIdDesc(eq(1L), any(Pageable.class)))
                .thenReturn(List.of(closetSave));

        ClosetSaveResponse.ClosetSaveList response = closetSaveService.getClosetSaves(1L, null, null);

        assertThat(response.items().get(0).thumbnailUrl()).isNull();
        assertThat(response.items().get(0).tags()).isEmpty();
        verify(trendContentRepository, never()).findAllById(any());
    }

    @Test
    void getClosetSavesUsesCursorCreatedAtAndIdForNextPage() {
        LocalDateTime cursorCreatedAt = LocalDateTime.of(2026, 7, 19, 12, 0);
        ClosetSave cursorClosetSave = createClosetSave(100L, ClosetTargetType.LOOKBOOK, 1L, cursorCreatedAt);
        ClosetSave nextClosetSave = createClosetSave(99L, ClosetTargetType.LOOKBOOK, 99L, cursorCreatedAt.minusMinutes(1));
        when(closetSaveRepository.findByIdAndMemberId(100L, 1L)).thenReturn(Optional.of(cursorClosetSave));
        when(closetSaveRepository.findNextPage(eq(1L), eq(cursorCreatedAt), eq(100L), any(Pageable.class)))
                .thenReturn(List.of(nextClosetSave));

        ClosetSaveResponse.ClosetSaveList response = closetSaveService.getClosetSaves(1L, null, 100L);

        assertThat(response.items())
                .extracting(ClosetSaveResponse.ClosetSaveItem::targetId)
                .containsExactly(99L);
    }

    @Test
    void getClosetSavesUsesCursorWithTargetTypeFilterForNextPage() {
        LocalDateTime cursorCreatedAt = LocalDateTime.of(2026, 7, 19, 12, 0);
        ClosetSave cursorClosetSave = createClosetSave(100L, ClosetTargetType.TREND, 1L, cursorCreatedAt);
        ClosetSave nextClosetSave = createClosetSave(99L, ClosetTargetType.TREND, 99L, cursorCreatedAt.minusMinutes(1));
        when(closetSaveRepository.findByIdAndMemberId(100L, 1L)).thenReturn(Optional.of(cursorClosetSave));
        when(closetSaveRepository.findNextPageByTargetType(
                eq(1L), eq(ClosetTargetType.TREND), eq(cursorCreatedAt), eq(100L), any(Pageable.class)))
                .thenReturn(List.of(nextClosetSave));

        ClosetSaveResponse.ClosetSaveList response =
                closetSaveService.getClosetSaves(1L, ClosetTargetType.TREND, 100L);

        assertThat(response.items())
                .extracting(ClosetSaveResponse.ClosetSaveItem::targetId)
                .containsExactly(99L);
    }

    @Test
    void getClosetSavesFailsWhenCursorDoesNotExistOrNotOwned() {
        when(closetSaveRepository.findByIdAndMemberId(999L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> closetSaveService.getClosetSaves(1L, null, 999L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CLOSET_NOT_FOUND)
                );
    }

    private ClosetSave createClosetSave(
            Long id,
            ClosetTargetType targetType,
            Long targetId,
            LocalDateTime createdAt
    ) {
        ClosetSave closetSave = ClosetSave.create(member, targetType, targetId);
        ReflectionTestUtils.setField(closetSave, "id", id);
        ReflectionTestUtils.setField(closetSave, "createdAt", createdAt);
        return closetSave;
    }
}

package com.fitback.backend.domain.lookbook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.lookbook.dto.LookbookRequest;
import com.fitback.backend.domain.lookbook.dto.LookbookResponse;
import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.lookbook.entity.LookbookTag;
import com.fitback.backend.domain.lookbook.repository.LookbookLikeRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookTagRepository;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.entity.MemberRole;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.mock.TagRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LookbookServiceTest {

    @Mock
    private LookbookRepository lookbookRepository;

    @Mock
    private LookbookTagRepository lookbookTagRepository;

    @Mock
    private LookbookLikeRepository lookbookLikeRepository;

    @Mock
    private TagRepository tagRepository;

    @Mock
    private LookbookLikeCommandService lookbookLikeCommandService;

    @InjectMocks
    private LookbookService lookbookService;

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
    void createLookbookSavesLookbookAndTagRelations() {
        LookbookRequest.LookbookCreate request = createRequest(List.of(10L, 20L, 10L));
        when(tagRepository.findAllById(List.of(10L, 20L)))
                .thenReturn(List.of(minimalTag, streetTag));
        when(lookbookRepository.save(any(Lookbook.class))).thenAnswer(invocation -> {
            Lookbook lookbook = invocation.getArgument(0);
            ReflectionTestUtils.setField(lookbook, "id", 100L);
            return lookbook;
        });

        LookbookResponse.LookbookCreate response = lookbookService.createLookbook(member, request);

        assertThat(response.lookbookId()).isEqualTo(100L);
        assertThat(response.memberId()).isEqualTo(1L);
        assertThat(response.originalImageUrl()).isEqualTo("https://s3.example.com/original.jpg");
        assertThat(response.matchedImageUrl()).isEqualTo("https://s3.example.com/matched.jpg");
        assertThat(response.tagIds()).containsExactly(10L, 20L);
        assertThat(response.purchaseUrl()).isEqualTo("https://shop.example.com/item");
        assertThat(response.comment()).isEqualTo("합리적인 가격으로 완성한 룩입니다.");
        assertThat(response.likeCount()).isZero();
        verify(lookbookTagRepository).saveAll(anyList());
    }

    @Test
    void createLookbookFailsWhenTagDoesNotExist() {
        LookbookRequest.LookbookCreate request = createRequest(List.of(10L, 999L));
        when(tagRepository.findAllById(List.of(10L, 999L)))
                .thenReturn(List.of(minimalTag));

        assertThatThrownBy(() -> lookbookService.createLookbook(member, request))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(exception.getMessage()).contains("999");
                });
        verify(lookbookRepository, never()).save(any());
        verify(lookbookTagRepository, never()).saveAll(anyList());
    }

    @Test
    void getLookbookDetailReturnsAuthorTagsAndIsLiked() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 16, 12, 0);
        Lookbook lookbook = createPersistedLookbook(createdAt);
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(lookbook));
        when(lookbookTagRepository.findAllByLookbookIdOrderByIdAsc(100L))
                .thenReturn(List.of(
                        LookbookTag.create(lookbook, minimalTag),
                        LookbookTag.create(lookbook, streetTag)
                ));
        when(lookbookLikeRepository.existsByLookbookIdAndMemberId(100L, 1L))
                .thenReturn(true);

        LookbookResponse.LookbookDetail response = lookbookService.getLookbookDetail(100L, member);

        assertThat(response.originalImageUrl()).isEqualTo("https://s3.example.com/original.jpg");
        assertThat(response.matchedImageUrl()).isEqualTo("https://s3.example.com/matched.jpg");
        assertThat(response.authorNickname()).isEqualTo("fitback");
        assertThat(response.purchaseUrl()).isEqualTo("https://shop.example.com/item");
        assertThat(response.tags()).containsExactly("미니멀", "스트릿");
        assertThat(response.likeCount()).isEqualTo(5);
        assertThat(response.isLiked()).isTrue();
    }

    @Test
    void getLookbookDetailReturnsIsLikedFalseForAnonymousMember() {
        Lookbook lookbook = createPersistedLookbook(LocalDateTime.of(2026, 7, 16, 12, 0));
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(lookbook));
        when(lookbookTagRepository.findAllByLookbookIdOrderByIdAsc(100L))
                .thenReturn(List.of());

        LookbookResponse.LookbookDetail response = lookbookService.getLookbookDetail(100L, null);

        assertThat(response.isLiked()).isFalse();
        verify(lookbookLikeRepository, never()).existsByLookbookIdAndMemberId(any(), any());
    }

    @Test
    void getLookbookDetailFailsWhenLookbookDoesNotExist() {
        when(lookbookRepository.findByIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lookbookService.getLookbookDetail(999L, null))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(exception.getMessage()).isEqualTo("룩북을 찾을 수 없습니다.");
                });
    }

    @Test
    void deleteLookbookSoftDeletesOwnersLookbook() {
        Lookbook lookbook = createPersistedLookbook(LocalDateTime.of(2026, 7, 18, 12, 0));
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(lookbook));

        lookbookService.deleteLookbook(100L, member);

        assertThat(lookbook.getDeletedAt()).isNotNull();
    }

    @Test
    void deleteLookbookAllowsAdminMember() {
        Lookbook lookbook = createPersistedLookbook(LocalDateTime.of(2026, 7, 18, 12, 0));
        Member admin = Member.create("admin@fitback.com", "admin", "password", LoginProvider.EMAIL);
        ReflectionTestUtils.setField(admin, "id", 2L);
        admin.changeRole(MemberRole.ADMIN);
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(lookbook));

        lookbookService.deleteLookbook(100L, admin);

        assertThat(lookbook.getDeletedAt()).isNotNull();
    }

    @Test
    void deleteLookbookRejectsMemberWithoutPermission() {
        Lookbook lookbook = createPersistedLookbook(LocalDateTime.of(2026, 7, 18, 12, 0));
        Member otherMember = Member.create(
                "other@fitback.com",
                "other",
                "password",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(otherMember, "id", 2L);
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(lookbook));

        assertThatThrownBy(() -> lookbookService.deleteLookbook(100L, otherMember))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN)
                );
        assertThat(lookbook.getDeletedAt()).isNull();
    }

    @Test
    void deleteLookbookFailsWhenLookbookIsAlreadyDeleted() {
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lookbookService.deleteLookbook(100L, member))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND)
                );
    }

    @Test
    void likeLookbookReturnsChangedLikeCount() {
        when(lookbookLikeCommandService.createLike(100L, member)).thenReturn(6);

        LookbookResponse.LookbookLike response = lookbookService.likeLookbook(100L, member);

        assertThat(response.likeCount()).isEqualTo(6);
    }

    @Test
    void likeLookbookReturnsCurrentCountWhenAlreadyLiked() {
        when(lookbookLikeCommandService.createLike(100L, member))
                .thenThrow(new DataIntegrityViolationException("duplicate like"));
        when(lookbookLikeRepository.existsByLookbookIdAndMemberId(100L, 1L)).thenReturn(true);
        when(lookbookRepository.findLikeCountByIdAndDeletedAtIsNull(100L))
                .thenReturn(Optional.of(5));

        LookbookResponse.LookbookLike response = lookbookService.likeLookbook(100L, member);

        assertThat(response.likeCount()).isEqualTo(5);
    }

    @Test
    void likeLookbookRethrowsUnexpectedIntegrityViolation() {
        DataIntegrityViolationException exception =
                new DataIntegrityViolationException("unexpected constraint violation");
        when(lookbookLikeCommandService.createLike(100L, member)).thenThrow(exception);
        when(lookbookLikeRepository.existsByLookbookIdAndMemberId(100L, 1L)).thenReturn(false);

        assertThatThrownBy(() -> lookbookService.likeLookbook(100L, member))
                .isSameAs(exception);
    }

    @Test
    void deleteLookbookLikeReturnsChangedLikeCount() {
        when(lookbookLikeCommandService.deleteLike(100L, member)).thenReturn(4);

        LookbookResponse.LookbookLike response = lookbookService.deleteLookbookLike(100L, member);

        assertThat(response.likeCount()).isEqualTo(4);
    }

    @Test
    void getLookbooksUsesRequestedPageSizeAndReturnsNextCursor() {
        LocalDateTime latestCreatedAt = LocalDateTime.of(2026, 7, 16, 12, 0);
        List<Lookbook> lookbookPage = IntStream.range(0, 6)
                .mapToObj(index -> createListLookbook(
                        100L - index,
                        latestCreatedAt.minusMinutes(index)
                ))
                .toList();
        List<Long> returnedLookbookIds = lookbookPage.subList(0, 5)
                .stream()
                .map(Lookbook::getId)
                .toList();
        when(lookbookRepository.findAllByDeletedAtIsNullOrderByCreatedAtDescIdDesc(
                any(Pageable.class)
        ))
                .thenReturn(lookbookPage);
        when(lookbookTagRepository.findAllByLookbookIdInOrderByIdAsc(returnedLookbookIds))
                .thenReturn(List.of(LookbookTag.create(lookbookPage.get(0), minimalTag)));
        when(lookbookLikeRepository.findLikedLookbookIds(1L, returnedLookbookIds))
                .thenReturn(Set.of(100L));

        LookbookResponse.LookbookList response = lookbookService.getLookbooks(null, 5, member);

        assertThat(response.items()).hasSize(5);
        assertThat(response.items().get(0).lookbookId()).isEqualTo(100L);
        assertThat(response.items().get(0).authorNickname()).isEqualTo("fitback");
        assertThat(response.items().get(0).authorProfileImageUrl())
                .isEqualTo("https://s3.example.com/profile.jpg");
        assertThat(response.items().get(0).isLiked()).isTrue();
        assertThat(response.items().get(0).tags()).containsExactly("미니멀");
        assertThat(response.items().get(1).isLiked()).isFalse();
        assertThat(response.nextCursor()).isEqualTo(96L);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.pageSize()).isEqualTo(5);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(lookbookRepository)
                .findAllByDeletedAtIsNullOrderByCreatedAtDescIdDesc(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(6);
    }

    @Test
    void getLookbooksReturnsLikedByMeFalseForAnonymousMember() {
        Lookbook lookbook = createListLookbook(100L, LocalDateTime.of(2026, 7, 16, 12, 0));
        when(lookbookRepository.findAllByDeletedAtIsNullOrderByCreatedAtDescIdDesc(
                any(Pageable.class)
        ))
                .thenReturn(List.of(lookbook));
        when(lookbookTagRepository.findAllByLookbookIdInOrderByIdAsc(List.of(100L)))
                .thenReturn(List.of());

        LookbookResponse.LookbookList response = lookbookService.getLookbooks(null, null, null);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).isLiked()).isFalse();
        assertThat(response.nextCursor()).isNull();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.pageSize()).isEqualTo(20);
        verify(lookbookLikeRepository, never()).findLikedLookbookIds(any(), anyList());
    }

    @Test
    void getLookbooksUsesCursorCreatedAtAndIdForNextPage() {
        LocalDateTime cursorCreatedAt = LocalDateTime.of(2026, 7, 16, 12, 0);
        Lookbook cursorLookbook = createListLookbook(100L, cursorCreatedAt);
        Lookbook nextLookbook = createListLookbook(99L, cursorCreatedAt.minusMinutes(1));
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L))
                .thenReturn(Optional.of(cursorLookbook));
        when(lookbookRepository.findNextPage(
                eq(cursorCreatedAt),
                eq(100L),
                any(Pageable.class)
        )).thenReturn(List.of(nextLookbook));
        when(lookbookTagRepository.findAllByLookbookIdInOrderByIdAsc(List.of(99L)))
                .thenReturn(List.of());

        LookbookResponse.LookbookList response = lookbookService.getLookbooks(100L, 5, null);

        assertThat(response.items())
                .extracting(LookbookResponse.LookbookItem::lookbookId)
                .containsExactly(99L);
    }

    private LookbookRequest.LookbookCreate createRequest(List<Long> tagIds) {
        return new LookbookRequest.LookbookCreate(
                "https://s3.example.com/original.jpg",
                "https://s3.example.com/matched.jpg",
                tagIds,
                "https://shop.example.com/item",
                "합리적인 가격으로 완성한 룩입니다."
        );
    }

    private Lookbook createPersistedLookbook(LocalDateTime createdAt) {
        member.changeProfileImageUrl("https://s3.example.com/profile.jpg");
        Lookbook lookbook = Lookbook.create(
                member,
                "https://s3.example.com/original.jpg",
                "https://s3.example.com/matched.jpg",
                "https://shop.example.com/item",
                "합리적인 가격으로 완성한 룩입니다."
        );
        ReflectionTestUtils.setField(lookbook, "id", 100L);
        ReflectionTestUtils.setField(lookbook, "likeCount", 5);
        ReflectionTestUtils.setField(lookbook, "createdAt", createdAt);
        return lookbook;
    }

    private Lookbook createListLookbook(Long lookbookId, LocalDateTime createdAt) {
        member.changeProfileImageUrl("https://s3.example.com/profile.jpg");
        Lookbook lookbook = Lookbook.create(
                member,
                "https://s3.example.com/original-" + lookbookId + ".jpg",
                "https://s3.example.com/matched-" + lookbookId + ".jpg",
                null,
                null
        );
        ReflectionTestUtils.setField(lookbook, "id", lookbookId);
        ReflectionTestUtils.setField(lookbook, "createdAt", createdAt);
        return lookbook;
    }
}

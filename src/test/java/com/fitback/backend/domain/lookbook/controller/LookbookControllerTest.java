package com.fitback.backend.domain.lookbook.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.lookbook.dto.LookbookRequest;
import com.fitback.backend.domain.lookbook.dto.LookbookResponse;
import com.fitback.backend.domain.lookbook.service.LookbookService;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.mock.AuthMember;
import com.fitback.backend.global.response.ApiResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LookbookControllerTest {

    @Mock
    private LookbookService lookbookService;

    @InjectMocks
    private LookbookController lookbookController;

    private Member member;
    private AuthMember authMember;
    private LookbookRequest.LookbookCreate request;

    @BeforeEach
    void setUp() {
        member = Member.create("member@fitback.com", "fitback", "password", LoginProvider.EMAIL);
        ReflectionTestUtils.setField(member, "id", 1L);
        authMember = new AuthMember(member);
        request = new LookbookRequest.LookbookCreate(
                "https://s3.example.com/original.jpg",
                "https://s3.example.com/matched.jpg",
                List.of(10L, 20L),
                null,
                "합리적인 가격으로 완성한 룩입니다."
        );
    }

    @Test
    void createLookbookReturnsCreatedResponse() {
        LookbookResponse.LookbookCreate serviceResponse = LookbookResponse.LookbookCreate.builder()
                .lookbookId(100L)
                .memberId(1L)
                .originalImageUrl(request.originalImageUrl())
                .matchedImageUrl(request.matchedImageUrl())
                .tagIds(request.tagIds())
                .purchaseUrl(request.purchaseUrl())
                .comment(request.comment())
                .likeCount(0)
                .build();
        when(lookbookService.createLookbook(member, request)).thenReturn(serviceResponse);

        ApiResponse<LookbookResponse.LookbookCreate> response =
                lookbookController.createLookbook(authMember, request);

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("COMMON200_1");
        assertThat(response.data()).isEqualTo(serviceResponse);
        verify(lookbookService).createLookbook(member, request);
    }

    @Test
    void createLookbookFailsWithoutAuthenticationPrincipal() {
        assertThatThrownBy(() -> lookbookController.createLookbook(null, request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED)
                );
    }

    @Test
    void getLookbooksAllowsAnonymousMember() {
        LookbookResponse.LookbookItem item = LookbookResponse.LookbookItem.builder()
                .lookbookId(100L)
                .memberId(1L)
                .nickname("fitback")
                .profileImageUrl("https://s3.example.com/profile.jpg")
                .originalImageUrl("https://s3.example.com/original.jpg")
                .matchedImageUrl("https://s3.example.com/matched.jpg")
                .tags(List.of())
                .likeCount(5)
                .likedByMe(false)
                .createdAt(LocalDateTime.of(2026, 7, 16, 12, 0))
                .build();
        LookbookResponse.LookbookList serviceResponse = LookbookResponse.LookbookList.builder()
                .items(List.of(item))
                .nextCursor(null)
                .hasNext(false)
                .build();
        when(lookbookService.getLookbooks(null, null)).thenReturn(serviceResponse);

        ApiResponse<LookbookResponse.LookbookList> response =
                lookbookController.getLookbooks(null, null);

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("COMMON200_1");
        assertThat(response.data()).isEqualTo(serviceResponse);
        assertThat(response.data().items()).containsExactly(item);
        verify(lookbookService).getLookbooks(null, null);
    }

    @Test
    void deleteLookbookReturnsSuccessResponse() {
        ApiResponse<Void> response = lookbookController.deleteLookbook(100L, authMember);

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("COMMON200_1");
        assertThat(response.data()).isNull();
        verify(lookbookService).deleteLookbook(100L, member);
    }

    @Test
    void deleteLookbookFailsWithoutAuthenticationPrincipal() {
        assertThatThrownBy(() -> lookbookController.deleteLookbook(100L, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED)
                );
    }

    @Test
    void getLookbookDetailAllowsAnonymousMember() {
        LookbookResponse.LookbookDetail serviceResponse = LookbookResponse.LookbookDetail.builder()
                .lookbookId(100L)
                .memberId(1L)
                .nickname("fitback")
                .profileImageUrl("https://s3.example.com/profile.jpg")
                .originalImageUrl("https://s3.example.com/original.jpg")
                .matchedImageUrl("https://s3.example.com/matched.jpg")
                .tags(List.of(
                        LookbookResponse.TagInfo.builder()
                                .tagId(10L)
                                .tagName("미니멀")
                                .tagType(TagType.DETAIL)
                                .build()
                ))
                .purchaseUrl("https://shop.example.com/item")
                .comment("합리적인 가격으로 완성한 룩입니다.")
                .likeCount(5)
                .likedByMe(false)
                .createdAt(LocalDateTime.of(2026, 7, 16, 12, 0))
                .build();
        when(lookbookService.getLookbookDetail(100L, null)).thenReturn(serviceResponse);

        ApiResponse<LookbookResponse.LookbookDetail> response =
                lookbookController.getLookbookDetail(100L, null);

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("COMMON200_1");
        assertThat(response.data()).isEqualTo(serviceResponse);
        assertThat(response.data().likedByMe()).isFalse();
        verify(lookbookService).getLookbookDetail(100L, null);
    }
}

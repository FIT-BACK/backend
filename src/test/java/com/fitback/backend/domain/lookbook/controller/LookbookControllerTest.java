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
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.mock.AuthMember;
import com.fitback.backend.global.response.ApiResponse;
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
                null,
                List.of(10L, 20L),
                "합리적인 가격으로 완성한 룩입니다."
        );
    }

    @Test
    void createLookbookReturnsCreatedResponse() {
        LookbookResponse.LookbookCreate serviceResponse = LookbookResponse.LookbookCreate.builder()
                .lookbookId(100L)
                .build();
        when(lookbookService.createLookbook(member, request)).thenReturn(serviceResponse);

        ApiResponse<LookbookResponse.LookbookCreate> response =
                lookbookController.createLookbook(authMember, request);

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("COMMON201_1");
        assertThat(response.message()).isEqualTo("리소스가 생성되었습니다.");
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
                .originalImageUrl("https://s3.example.com/original.jpg")
                .matchedImageUrl("https://s3.example.com/matched.jpg")
                .authorNickname("fitback")
                .authorProfileImageUrl("https://s3.example.com/profile.jpg")
                .tags(List.of("미니멀"))
                .likeCount(5)
                .isLiked(false)
                .build();
        LookbookResponse.LookbookList serviceResponse = LookbookResponse.LookbookList.builder()
                .items(List.of(item))
                .nextCursor(null)
                .hasNext(false)
                .pageSize(20)
                .build();
        when(lookbookService.getLookbooks(null, 20, null)).thenReturn(serviceResponse);

        ApiResponse<LookbookResponse.LookbookList> response =
                lookbookController.getLookbooks(null, 20, null);

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("COMMON200_1");
        assertThat(response.data()).isEqualTo(serviceResponse);
        assertThat(response.data().items()).containsExactly(item);
        assertThat(response.data().pageSize()).isEqualTo(20);
        verify(lookbookService).getLookbooks(null, 20, null);
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
    void likeLookbookReturnsChangedLikeCount() {
        LookbookResponse.LookbookLike serviceResponse = LookbookResponse.LookbookLike.builder()
                .isLiked(true)
                .likeCount(6)
                .build();
        when(lookbookService.likeLookbook(100L, member)).thenReturn(serviceResponse);

        ApiResponse<LookbookResponse.LookbookLike> response =
                lookbookController.likeLookbook(100L, authMember);

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("COMMON200_1");
        assertThat(response.data()).isEqualTo(serviceResponse);
        assertThat(response.data().isLiked()).isTrue();
        assertThat(response.data().likeCount()).isEqualTo(6);
        verify(lookbookService).likeLookbook(100L, member);
    }

    @Test
    void likeLookbookFailsWithoutAuthenticationPrincipal() {
        assertThatThrownBy(() -> lookbookController.likeLookbook(100L, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED)
                );
    }

    @Test
    void deleteLookbookLikeReturnsChangedLikeCount() {
        LookbookResponse.LookbookUnlike serviceResponse = LookbookResponse.LookbookUnlike.builder()
                .likeCount(4)
                .build();
        when(lookbookService.deleteLookbookLike(100L, member)).thenReturn(serviceResponse);

        ApiResponse<LookbookResponse.LookbookUnlike> response =
                lookbookController.deleteLookbookLike(100L, authMember);

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("COMMON200_1");
        assertThat(response.data()).isEqualTo(serviceResponse);
        assertThat(response.data().likeCount()).isEqualTo(4);
        verify(lookbookService).deleteLookbookLike(100L, member);
    }

    @Test
    void deleteLookbookLikeFailsWithoutAuthenticationPrincipal() {
        assertThatThrownBy(() -> lookbookController.deleteLookbookLike(100L, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED)
                );
    }

    @Test
    void getLookbookDetailAllowsAnonymousMember() {
        LookbookResponse.LookbookDetail serviceResponse = LookbookResponse.LookbookDetail.builder()
                .originalImageUrl("https://s3.example.com/original.jpg")
                .matchedImageUrl("https://s3.example.com/matched.jpg")
                .authorNickname("fitback")
                .purchaseUrl("https://shop.example.com/item")
                .tags(List.of("미니멀"))
                .likeCount(5)
                .isLiked(false)
                .build();
        when(lookbookService.getLookbookDetail(100L, null)).thenReturn(serviceResponse);

        ApiResponse<LookbookResponse.LookbookDetail> response =
                lookbookController.getLookbookDetail(100L, null);

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("COMMON200_1");
        assertThat(response.data()).isEqualTo(serviceResponse);
        assertThat(response.data().isLiked()).isFalse();
        verify(lookbookService).getLookbookDetail(100L, null);
    }
}

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
}

package com.fitback.backend.domain.contentsearch.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.contentsearch.dto.ContentSearchResponse;
import com.fitback.backend.domain.contentsearch.service.ContentSearchService;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.global.response.ApiResponse;
import com.fitback.backend.global.security.entity.AuthMember;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContentSearchControllerTest {

    private final ContentSearchService contentSearchService =
            mock(ContentSearchService.class);
    private final ContentSearchController controller = new ContentSearchController(
            contentSearchService
    );

    @Test
    void passesAuthenticatedMemberAndReturnsCommonSuccessEnvelope() {
        Member member = Member.create(
                "member@fitback.com",
                "fitback",
                "password",
                LoginProvider.EMAIL
        );
        AuthMember authMember = new AuthMember(member);
        ContentSearchResponse serviceResponse = new ContentSearchResponse(
                List.of(),
                List.of()
        );
        when(contentSearchService.search("minimal", member)).thenReturn(serviceResponse);

        ApiResponse<ContentSearchResponse> response = controller.search(
                "minimal",
                authMember
        );

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("COMMON200_1");
        assertThat(response.data()).isEqualTo(serviceResponse);
        verify(contentSearchService).search("minimal", member);
    }

    @Test
    void passesNullMemberForAnonymousRequest() {
        ContentSearchResponse serviceResponse = new ContentSearchResponse(
                List.of(),
                List.of()
        );
        when(contentSearchService.search("minimal", null)).thenReturn(serviceResponse);

        controller.search("minimal", null);

        verify(contentSearchService).search("minimal", null);
    }
}

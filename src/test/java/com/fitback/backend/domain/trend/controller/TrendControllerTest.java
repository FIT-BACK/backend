package com.fitback.backend.domain.trend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.lookbook.dto.LookbookResponse;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.trend.dto.TrendResponse;
import com.fitback.backend.domain.trend.service.TrendService;
import com.fitback.backend.global.response.ApiResponse;
import com.fitback.backend.global.security.entity.AuthMember;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TrendControllerTest {

    @Mock
    private TrendService trendService;

    @InjectMocks
    private TrendController trendController;

    private AuthMember authMemberWithId(Long memberId) {
        Member member = Member.create("member@fitback.com", "fitback", "password", LoginProvider.EMAIL);
        ReflectionTestUtils.setField(member, "id", memberId);
        return new AuthMember(member);
    }

    @Test
    void getTrendsReturnsSuccessResponse() {
        TrendResponse.TrendItem item = TrendResponse.TrendItem.builder()
                .trendId(1L)
                .title("스트릿 무드")
                .imageUrl("https://cdn.fitback.app/trends/1.jpg")
                .tags(List.of("스트릿"))
                .isSaved(false)
                .build();
        TrendResponse.TrendList serviceResponse = TrendResponse.TrendList.builder()
                .items(List.of(item))
                .nextCursor(null)
                .hasNext(false)
                .pageSize(10)
                .build();
        when(trendService.getTrends(null, null, null)).thenReturn(serviceResponse);

        ApiResponse<TrendResponse.TrendList> response = trendController.getTrends(null, null, null);

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("COMMON200_1");
        assertThat(response.data()).isEqualTo(serviceResponse);
        assertThat(response.data().items()).containsExactly(item);
        verify(trendService).getTrends(null, null, null);
    }

    @Test
    void getTrendsPassesCursorAndTagToService() {
        TrendResponse.TrendList serviceResponse = TrendResponse.TrendList.builder()
                .items(List.of())
                .nextCursor(null)
                .hasNext(false)
                .pageSize(10)
                .build();
        when(trendService.getTrends(2L, "미니멀", null)).thenReturn(serviceResponse);

        trendController.getTrends(2L, "미니멀", null);

        verify(trendService).getTrends(2L, "미니멀", null);
    }

    @Test
    void getTrendsPassesAuthenticatedMemberToService() {
        AuthMember authMember = authMemberWithId(7L);
        TrendResponse.TrendList serviceResponse = TrendResponse.TrendList.builder()
                .items(List.of())
                .nextCursor(null)
                .hasNext(false)
                .pageSize(10)
                .build();
        when(trendService.getTrends(null, null, authMember.getMember())).thenReturn(serviceResponse);

        trendController.getTrends(null, null, authMember);

        verify(trendService).getTrends(null, null, authMember.getMember());
    }

    @Test
    void getTrendDetailReturnsSuccessResponse() {
        TrendResponse.TrendDetail serviceResponse = TrendResponse.TrendDetail.builder()
                .title("미니멀룩")
                .imageUrl("https://exampletrends.jpg")
                .description("무채색 아이템을 중심으로 구성한 미니멀 코디 모음")
                .tags(List.of("미니멀", "무채색"))
                .isSaved(false)
                .build();
        when(trendService.getTrendDetail(1L, null)).thenReturn(serviceResponse);

        ApiResponse<TrendResponse.TrendDetail> response = trendController.getTrendDetail(1L, null);

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("COMMON200_1");
        assertThat(response.data()).isEqualTo(serviceResponse);
        verify(trendService).getTrendDetail(1L, null);
    }

    @Test
    void getTrendDetailPassesAuthenticatedMemberToService() {
        AuthMember authMember = authMemberWithId(7L);
        TrendResponse.TrendDetail serviceResponse = TrendResponse.TrendDetail.builder()
                .title("미니멀룩")
                .imageUrl("https://exampletrends.jpg")
                .description("무채색 아이템을 중심으로 구성한 미니멀 코디 모음")
                .tags(List.of("미니멀"))
                .isSaved(true)
                .build();
        when(trendService.getTrendDetail(1L, authMember.getMember())).thenReturn(serviceResponse);

        ApiResponse<TrendResponse.TrendDetail> response = trendController.getTrendDetail(1L, authMember);

        assertThat(response.data().isSaved()).isTrue();
        verify(trendService).getTrendDetail(1L, authMember.getMember());
    }

    @Test
    void getRelatedLookbooksPassesCursorAndAuthenticatedMemberToService() {
        AuthMember authMember = authMemberWithId(7L);
        LookbookResponse.LookbookList serviceResponse = LookbookResponse.LookbookList.builder()
                .items(List.of())
                .nextCursor(null)
                .hasNext(false)
                .pageSize(3)
                .build();
        when(trendService.getRelatedLookbooks(1L, 10L, authMember.getMember()))
                .thenReturn(serviceResponse);

        ApiResponse<LookbookResponse.LookbookList> response =
                trendController.getRelatedLookbooks(1L, 10L, authMember);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(serviceResponse);
        verify(trendService).getRelatedLookbooks(1L, 10L, authMember.getMember());
    }
}

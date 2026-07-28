package com.fitback.backend.global.security;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitback.backend.domain.lookbook.dto.LookbookResponse;
import com.fitback.backend.domain.lookbook.service.LookbookService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class SecurityLookbookAccessIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LookbookService lookbookService;

    //비로그인 룩북 목록 조회 허용
    @Test
    void allowsAnonymousLookbookListRead() throws Exception {
        LookbookResponse.LookbookList response = LookbookResponse.LookbookList.builder()
                .items(List.of())
                .pageSize(20)
                .hasNext(false)
                .build();
        when(lookbookService.getLookbooks(null, 20, null, null)).thenReturn(response);

        //get /api/v1/lookbooks 요청 생성
        mockMvc.perform(get("/api/v1/lookbooks"))
                .andExpect(status().isOk())     //http 200 확인
                .andExpect(jsonPath("$.success").value(true))   //응답 json에서 success가 true인지 확인
                .andExpect(jsonPath("$.code").value("COMMON200_1"));    //응답 코드 확인
    }

    //비로그인 룩북 상세 조회 허용
    @Test
    void allowsAnonymousLookbookDetailRead() throws Exception {
        LookbookResponse.LookbookDetail response = LookbookResponse.LookbookDetail.builder()
                .isLiked(false)
                .isOwner(false)
                .build();
        when(lookbookService.getLookbookDetail(1L, null)).thenReturn(response);

        // /api/v1/lookbooks/1 요청 생성 -> 검증
        mockMvc.perform(get("/api/v1/lookbooks/{lookbookId}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON200_1"))
                .andExpect(jsonPath("$.data.isLiked").value(false))
                .andExpect(jsonPath("$.data.isOwner").value(false));
    }

    //비로그인 룩북 생성 요청은 인증 필요
    @Test
    void requiresAuthenticationForLookbookCreate() throws Exception {
        //인증이 필요한 post /api/v1/lookbooks 요청
        mockMvc.perform(post("/api/v1/lookbooks"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    //비로그인 룩북 삭제 요청은 인증 필요
    @Test
    void requiresAuthenticationForLookbookDelete() throws Exception {
        //인증이 필요한 post /api/v1/lookbooks 요청
        mockMvc.perform(delete("/api/v1/lookbooks/{lookbookId}", 1L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }
}

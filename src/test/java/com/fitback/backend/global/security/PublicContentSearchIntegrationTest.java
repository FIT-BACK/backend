package com.fitback.backend.global.security;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitback.backend.domain.contentsearch.dto.ContentSearchResponse;
import com.fitback.backend.domain.contentsearch.service.ContentSearchService;
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
class PublicContentSearchIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContentSearchService contentSearchService;

    @Test
    void allowsAnonymousContentSearch() throws Exception {
        when(contentSearchService.search("minimal", null)).thenReturn(
                new ContentSearchResponse(List.of(), List.of())
        );

        mockMvc.perform(get("/api/v1/content-search")
                        .param("keyword", "minimal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON200_1"))
                .andExpect(jsonPath("$.data.trends").isArray())
                .andExpect(jsonPath("$.data.lookbooks").isArray());
    }
}

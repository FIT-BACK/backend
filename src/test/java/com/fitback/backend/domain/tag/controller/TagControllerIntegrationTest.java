package com.fitback.backend.domain.tag.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.domain.tag.repository.TagRepository;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class TagControllerIntegrationTest {

    private static final String TEST_EMAIL = "tag-list@fitback.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private MemberRepository memberRepository;

    @BeforeEach
    void cleanBefore() {
        cleanDatabase();
    }

    @AfterEach
    void cleanAfter() {
        cleanDatabase();
    }

    @Test
    void returnsFilteredTagsForAuthenticatedMember() throws Exception {
        String accessToken = signUpAndGetAccessToken();
        tagRepository.save(Tag.create("미니멀", TagType.DETAIL));
        tagRepository.save(Tag.create("미니백", TagType.DETAIL));
        tagRepository.save(Tag.create("블랙", TagType.COLOR));

        mockMvc.perform(get("/api/v1/tags")
                        .header("Authorization", bearer(accessToken))
                        .param("tagType", "DETAIL")
                        .param("query", "#미니")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].tagName").value("미니멀"))
                .andExpect(jsonPath("$.data.items[0].tagType").value("DETAIL"));
    }

    @Test
    void requiresAuthenticationAndValidLimit() throws Exception {
        String accessToken = signUpAndGetAccessToken();

        mockMvc.perform(get("/api/v1/tags"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/tags")
                        .header("Authorization", bearer(accessToken))
                        .param("limit", "51"))
                .andExpect(status().isBadRequest());
    }

    private String signUpAndGetAccessToken() throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email",
                                TEST_EMAIL,
                                "password",
                                "password123"
                        ))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).at("/data/accessToken").asText();
    }

    private void cleanDatabase() {
        tagRepository.deleteAll();
        memberRepository.findByEmail(TEST_EMAIL).ifPresent(memberRepository::delete);
    }

    private static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}

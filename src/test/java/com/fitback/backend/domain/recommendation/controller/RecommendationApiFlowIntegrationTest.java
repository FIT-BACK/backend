package com.fitback.backend.domain.recommendation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.product.repository.ProductRepository;
import com.fitback.backend.domain.product.repository.SavedProductRepository;
import com.fitback.backend.domain.recommendation.repository.RecommendedItemRepository;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.domain.tag.repository.TagRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class RecommendationApiFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SavedProductRepository savedProductRepository;

    @Autowired
    private RecommendedItemRepository recommendedItemRepository;

    @Autowired
    private AnalysisReportRepository analysisReportRepository;

    @Autowired
    private ProductRepository productRepository;

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
    void completesRecommendationSaveRegenerateAndDeleteFlow() throws Exception {
        String email = "recommendation-api-flow@fitback.com";
        String accessToken = signUpAndGetAccessToken(email);
        AnalysisReport report = createReport(email, "Fixture");
        Long tagId = report.getDisplayTags().getFirst().getId();
        String requestBody = recommendationRequest(tagId, 70);

        MvcResult generated = mockMvc.perform(post(
                                "/api/v1/analyses/{reportId}/recommendations",
                                report.getId()
                        )
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisTags.length()").value(2))
                .andExpect(jsonPath("$.data.matchPercentage").value(70))
                .andExpect(jsonPath("$.data.scoreVersion")
                        .value("SIMILARITY_THRESHOLD_V2"))
                .andExpect(jsonPath("$.data.recommendationStatus").value("CURRENT"))
                .andExpect(jsonPath("$.data.recommendationGroups.length()").value(8))
                .andReturn();
        JsonNode groups = responseData(generated).get("recommendationGroups");
        assertGroupsAreSortedAndBounded(groups);
        JsonNode firstItem = firstRecommendationItem(groups);
        long productId = firstItem.get("productId").asLong();
        assertThat(firstItem.get("purchaseUrl").asText()).startsWith("https://");

        mockMvc.perform(put("/api/v1/members/me/saved-products/{productId}", productId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.isSaved").value(true));
        mockMvc.perform(put("/api/v1/members/me/saved-products/{productId}", productId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isSaved").value(true));
        assertThat(savedProductRepository.count()).isEqualTo(1);

        assertSavedStateInAnalysis(accessToken, report.getId(), productId, true);
        mockMvc.perform(get("/api/v1/products/{productId}", productId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isSaved").value(true));

        MvcResult regenerated = mockMvc.perform(post(
                                "/api/v1/analyses/{reportId}/recommendations",
                                report.getId()
                        )
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(findProduct(
                responseData(regenerated).get("recommendationGroups"),
                productId
        ).get("isSaved").asBoolean()).isTrue();
        assertThat(savedProductRepository.count()).isEqualTo(1);

        mockMvc.perform(delete("/api/v1/members/me/saved-products/{productId}", productId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isSaved").value(false));
        mockMvc.perform(delete("/api/v1/members/me/saved-products/{productId}", productId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isSaved").value(false));

        assertSavedStateInAnalysis(accessToken, report.getId(), productId, false);
        mockMvc.perform(get("/api/v1/products/{productId}", productId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isSaved").value(false));
        assertThat(savedProductRepository.count()).isZero();
    }

    @Test
    void keepsEightEmptyGroupsWhenThresholdExcludesAllCandidates() throws Exception {
        String email = "recommendation-api-empty@fitback.com";
        String accessToken = signUpAndGetAccessToken(email);
        AnalysisReport report = createReport(email, "Fixture");
        Long tagId = report.getDisplayTags().getFirst().getId();

        MvcResult result = mockMvc.perform(post(
                                "/api/v1/analyses/{reportId}/recommendations",
                                report.getId()
                        )
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recommendationRequest(tagId, 100)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommendationStatus").value("CURRENT"))
                .andExpect(jsonPath("$.data.recommendationGroups.length()").value(8))
                .andReturn();

        JsonNode groups = responseData(result).get("recommendationGroups");
        for (JsonNode group : groups) {
            assertThat(group.get("items")).isEmpty();
        }
    }

    @Test
    void enforcesAuthenticationAndReportOwnershipAcrossTheFlow() throws Exception {
        String ownerEmail = "recommendation-api-owner@fitback.com";
        signUpAndGetAccessToken(ownerEmail);
        AnalysisReport report = createReport(ownerEmail, "Fixture");
        String otherToken = signUpAndGetAccessToken("recommendation-api-other@fitback.com");

        mockMvc.perform(post(
                        "/api/v1/analyses/{reportId}/recommendations",
                        report.getId()
                ))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
        mockMvc.perform(post(
                                "/api/v1/analyses/{reportId}/recommendations",
                                report.getId()
                        )
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS404_1"));
    }

    private void assertSavedStateInAnalysis(
            String accessToken,
            Long reportId,
            long productId,
            boolean expected
    ) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/analyses/{reportId}", reportId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tags.length()").value(2))
                .andReturn();
        JsonNode item = findProduct(
                responseData(result).get("recommendationGroups"),
                productId
        );
        assertThat(item.get("isSaved").asBoolean()).isEqualTo(expected);
    }

    private void assertGroupsAreSortedAndBounded(JsonNode groups) {
        assertThat(groups).hasSize(8);
        List<String> categories = new ArrayList<>();
        for (JsonNode group : groups) {
            categories.add(group.get("category").asText());
            JsonNode items = group.get("items");
            assertThat(items.size()).isLessThanOrEqualTo(10);
            BigDecimal previous = null;
            for (JsonNode item : items) {
                BigDecimal current = item.get("similarityScore").decimalValue();
                if (previous != null) {
                    assertThat(previous).isGreaterThanOrEqualTo(current);
                }
                previous = current;
            }
        }
        assertThat(categories).containsExactly(
                "OUTER",
                "TOP",
                "BOTTOM",
                "DRESS",
                "SHOES",
                "BAG",
                "ACCESSORY",
                "OTHER"
        );
    }

    private JsonNode firstRecommendationItem(JsonNode groups) {
        for (JsonNode group : groups) {
            if (!group.get("items").isEmpty()) {
                return group.get("items").get(0);
            }
        }
        throw new AssertionError("Expected at least one recommendation item");
    }

    private JsonNode findProduct(JsonNode groups, long productId) {
        for (JsonNode group : groups) {
            for (JsonNode item : group.get("items")) {
                if (item.get("productId").asLong() == productId) {
                    return item;
                }
            }
        }
        throw new AssertionError("Expected recommendation product " + productId);
    }

    private JsonNode responseData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private String recommendationRequest(Long tagId, int matchPercentage) {
        return objectMapper.writeValueAsString(Map.of(
                "confirmedTagIds", List.of(tagId),
                "customTagNames", List.of("고프코어"),
                "matchPercentage", matchPercentage
        ));
    }

    private AnalysisReport createReport(String email, String tagName) {
        Member member = memberRepository.findByEmail(email).orElseThrow();
        AnalysisReport report = AnalysisReport.create(
                member,
                "https://example.com/recommendation-flow-analysis.jpg",
                70
        );
        Tag tag = tagRepository.save(Tag.create(tagName, TagType.DETAIL));
        report.addAiSuggestedTag(tag);
        return analysisReportRepository.save(report);
    }

    private String signUpAndGetAccessToken(String email) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email",
                                email,
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
        savedProductRepository.deleteAll();
        recommendedItemRepository.deleteAll();
        analysisReportRepository.deleteAll();
        productRepository.deleteAll();
        tagRepository.deleteAll();
        memberRepository.deleteAll();
    }

    private static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}

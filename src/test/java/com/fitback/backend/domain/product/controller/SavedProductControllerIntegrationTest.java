package com.fitback.backend.domain.product.controller;

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
import java.util.ArrayList;
import java.util.HashSet;
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
class SavedProductControllerIntegrationTest {

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
    void savesListsAndDeletesMaterializedProductIdempotently() throws Exception {
        String accessToken = signUpAndGetAccessToken("saved-product@fitback.com");
        long productId = materializeFixtureProduct(accessToken);

        MvcResult created = mockMvc.perform(put(
                                "/api/v1/members/me/saved-products/{productId}",
                                productId
                        )
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("COMMON201_1"))
                .andExpect(jsonPath("$.data.productId").value(productId))
                .andExpect(jsonPath("$.data.isSaved").value(true))
                .andExpect(jsonPath("$.data.savedAt").isNotEmpty())
                .andReturn();
        String savedAt = objectMapper.readTree(created.getResponse().getContentAsString())
                .at("/data/savedAt")
                .asText();

        mockMvc.perform(put("/api/v1/members/me/saved-products/{productId}", productId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200_1"))
                .andExpect(jsonPath("$.data.savedAt").value(savedAt));
        assertThat(savedProductRepository.count()).isEqualTo(1);

        mockMvc.perform(get("/api/v1/members/me/saved-products")
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].productId").value(productId))
                .andExpect(jsonPath("$.data.items[0].name").value("Fixture Minimal Shirt"))
                .andExpect(jsonPath("$.data.items[0].dataStatus").value("LIVE"))
                .andExpect(jsonPath("$.data.items[0].savedAt").value(savedAt))
                .andExpect(jsonPath("$.data.nextCursor").isEmpty())
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.pageSize").value(10))
                .andExpect(jsonPath("$.data.partial").value(false))
                .andExpect(jsonPath("$.data.warnings").isEmpty());

        mockMvc.perform(get("/api/v1/products/{productId}", productId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isSaved").value(true));

        mockMvc.perform(delete("/api/v1/members/me/saved-products/{productId}", productId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(productId))
                .andExpect(jsonPath("$.data.isSaved").value(false))
                .andExpect(jsonPath("$.data.savedAt").doesNotExist());
        mockMvc.perform(delete("/api/v1/members/me/saved-products/{productId}", productId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isSaved").value(false));

        assertThat(savedProductRepository.count()).isZero();
    }

    @Test
    void exposesSavedStateInRecommendationAndValidatesRequests() throws Exception {
        String email = "saved-recommendation@fitback.com";
        String accessToken = signUpAndGetAccessToken(email);
        long productId = materializeFixtureProduct(accessToken);
        mockMvc.perform(put("/api/v1/members/me/saved-products/{productId}", productId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isCreated());

        AnalysisReport report = createReport(email, "Fixture");
        MvcResult result = mockMvc.perform(post(
                                "/api/v1/analyses/{reportId}/recommendations",
                                report.getId()
                        )
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode groups = objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/recommendationGroups");
        boolean savedStateFound = false;
        for (JsonNode group : groups) {
            for (JsonNode item : group.get("items")) {
                if (item.get("productId").asLong() == productId) {
                    assertThat(item.get("isSaved").asBoolean()).isTrue();
                    savedStateFound = true;
                }
            }
        }
        assertThat(savedStateFound).isTrue();

        mockMvc.perform(put("/api/v1/members/me/saved-products/{productId}", 999999L)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT404_1"));
        mockMvc.perform(get("/api/v1/members/me/saved-products")
                        .header("Authorization", bearer(accessToken))
                        .param("cursor", "invalid")
                        .param("pageSize", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
        mockMvc.perform(get("/api/v1/members/me/saved-products")
                        .header("Authorization", bearer(accessToken))
                        .param("pageSize", "21"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
        mockMvc.perform(get("/api/v1/members/me/saved-products"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    @Test
    void traversesSavedProductsWithOpaqueCursorWithoutDuplicates() throws Exception {
        String accessToken = signUpAndGetAccessToken("saved-cursor@fitback.com");
        List<Long> productIds = materializeFixtureProducts(accessToken);
        assertThat(productIds).hasSizeGreaterThanOrEqualTo(3);
        for (Long productId : productIds) {
            mockMvc.perform(put("/api/v1/members/me/saved-products/{productId}", productId)
                            .header("Authorization", bearer(accessToken)))
                    .andExpect(status().isCreated());
        }

        MvcResult firstPageResult = mockMvc.perform(get("/api/v1/members/me/saved-products")
                        .header("Authorization", bearer(accessToken))
                        .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.nextCursor").isNotEmpty())
                .andReturn();
        JsonNode firstPage = objectMapper.readTree(
                firstPageResult.getResponse().getContentAsString()
        ).get("data");

        MvcResult secondPageResult = mockMvc.perform(get("/api/v1/members/me/saved-products")
                        .header("Authorization", bearer(accessToken))
                        .param("cursor", firstPage.get("nextCursor").asText())
                        .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").isEmpty())
                .andReturn();
        JsonNode secondPage = objectMapper.readTree(
                secondPageResult.getResponse().getContentAsString()
        ).get("data");

        List<Long> traversedProductIds = new ArrayList<>();
        firstPage.get("items").forEach(item ->
                traversedProductIds.add(item.get("productId").asLong()));
        secondPage.get("items").forEach(item ->
                traversedProductIds.add(item.get("productId").asLong()));
        assertThat(traversedProductIds).hasSize(productIds.size());
        assertThat(new HashSet<>(traversedProductIds))
                .containsExactlyInAnyOrderElementsOf(productIds);
    }

    private long materializeFixtureProduct(String accessToken) throws Exception {
        MvcResult searchResult = mockMvc.perform(get("/api/v1/products")
                        .header("Authorization", bearer(accessToken))
                        .param("keyword", "Minimal"))
                .andExpect(status().isOk())
                .andReturn();
        String candidateToken = objectMapper
                .readTree(searchResult.getResponse().getContentAsString())
                .at("/data/items/0/candidateToken")
                .asText();
        MvcResult materialized = mockMvc.perform(post("/api/v1/product-references")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "candidateToken",
                                candidateToken
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(materialized.getResponse().getContentAsString())
                .at("/data/productId")
                .asLong();
    }

    private List<Long> materializeFixtureProducts(String accessToken) throws Exception {
        MvcResult searchResult = mockMvc.perform(get("/api/v1/products")
                        .header("Authorization", bearer(accessToken))
                        .param("keyword", "Fixture"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(searchResult.getResponse().getContentAsString())
                .at("/data/items");
        List<Long> productIds = new ArrayList<>();
        for (JsonNode item : items) {
            String candidateToken = item.get("candidateToken").asText();
            if (!candidateToken.isBlank()) {
                MvcResult materialized = mockMvc.perform(post("/api/v1/product-references")
                                .header("Authorization", bearer(accessToken))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "candidateToken",
                                        candidateToken
                                ))))
                        .andExpect(status().isCreated())
                        .andReturn();
                productIds.add(objectMapper
                        .readTree(materialized.getResponse().getContentAsString())
                        .at("/data/productId")
                        .asLong());
            }
        }
        return productIds;
    }

    private AnalysisReport createReport(String email, String tagName) {
        Member member = memberRepository.findByEmail(email).orElseThrow();
        AnalysisReport report = AnalysisReport.create(
                member,
                "https://example.com/saved-product-analysis.jpg",
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

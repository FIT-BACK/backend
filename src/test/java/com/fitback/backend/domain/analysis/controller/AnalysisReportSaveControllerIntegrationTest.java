package com.fitback.backend.domain.analysis.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.closet.repository.ClosetSaveRepository;
import com.fitback.backend.domain.closet.repository.SavedAnalysisItemRepository;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.repository.ProductRepository;
import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProductStorageMode;
import com.fitback.backend.domain.product.service.model.ProviderIdentityType;
import com.fitback.backend.domain.recommendation.entity.RecommendedItem;
import com.fitback.backend.domain.recommendation.repository.RecommendedItemRepository;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.domain.tag.repository.TagRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
class AnalysisReportSaveControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SavedAnalysisItemRepository savedAnalysisItemRepository;

    @Autowired
    private ClosetSaveRepository closetSaveRepository;

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
    void savesListsReadsAndUnsavesSelectedRecommendationSnapshots() throws Exception {
        String email = "saved-report@fitback.com";
        String accessToken = signUpAndGetAccessToken(email);
        Member member = memberRepository.findByEmail(email).orElseThrow();
        AnalysisReport savedReport = createCurrentReport(member);
        createUnsavedReport(member);
        List<RecommendedItem> recommendations = createRecommendations(savedReport);

        List<Map<String, Object>> selectedItems = recommendations.stream()
                .map(item -> Map.<String, Object>of(
                        "category",
                        item.getCategory().name(),
                        "productId",
                        item.getProduct().getId()
                ))
                .toList();
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "selectedItems",
                selectedItems
        ));

        MvcResult created = mockMvc.perform(put(
                                "/api/v1/analyses/{reportId}/save",
                                savedReport.getId()
                        )
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("COMMON201_1"))
                .andExpect(jsonPath("$.data.reportId").value(savedReport.getId()))
                .andExpect(jsonPath("$.data.saved").value(true))
                .andExpect(jsonPath("$.data.savedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.selectedItems.length()").value(2))
                .andReturn();
        String savedAt = objectMapper.readTree(created.getResponse().getContentAsString())
                .at("/data/savedAt")
                .asText();

        mockMvc.perform(put("/api/v1/analyses/{reportId}/save", savedReport.getId())
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200_1"))
                .andExpect(jsonPath("$.data.savedAt").value(savedAt));
        assertThat(closetSaveRepository.count()).isEqualTo(1);
        assertThat(savedAnalysisItemRepository.count()).isEqualTo(2);

        recommendedItemRepository.deleteAll();

        mockMvc.perform(get("/api/v1/analyses")
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].reportId").value(savedReport.getId()))
                .andExpect(jsonPath("$.data.items[0].savedAt").value(savedAt));

        MvcResult detail = mockMvc.perform(get(
                                "/api/v1/analyses/{reportId}",
                                savedReport.getId()
                        )
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(true))
                .andExpect(jsonPath("$.data.savedAt").value(savedAt))
                .andExpect(jsonPath("$.data.selectedItems.length()").value(2))
                .andReturn();
        JsonNode detailItems = objectMapper
                .readTree(detail.getResponse().getContentAsString())
                .at("/data/selectedItems");
        assertThat(detailItems)
                .extracting(item -> item.get("category").asText())
                .containsExactlyInAnyOrder("TOP", "BOTTOM");
        assertThat(detailItems)
                .extracting(item -> item.get("name").asText())
                .containsExactlyInAnyOrder("오버핏 셔츠", "와이드 슬랙스");

        mockMvc.perform(delete("/api/v1/analyses/{reportId}/save", savedReport.getId())
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportId").value(savedReport.getId()))
                .andExpect(jsonPath("$.data.saved").value(false))
                .andExpect(jsonPath("$.data.savedAt").doesNotExist())
                .andExpect(jsonPath("$.data.selectedItems").isEmpty());
        mockMvc.perform(delete("/api/v1/analyses/{reportId}/save", savedReport.getId())
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(false));

        mockMvc.perform(get("/api/v1/analyses")
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
        mockMvc.perform(get("/api/v1/analyses/{reportId}", savedReport.getId())
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(false))
                .andExpect(jsonPath("$.data.selectedItems").isEmpty());
    }

    @Test
    void rejectsMissingCategoryAndAnotherMembersReport() throws Exception {
        String ownerToken = signUpAndGetAccessToken("report-owner@fitback.com");
        String otherToken = signUpAndGetAccessToken("report-other@fitback.com");
        Member owner = memberRepository.findByEmail("report-owner@fitback.com").orElseThrow();
        AnalysisReport report = createCurrentReport(owner);
        List<RecommendedItem> recommendations = createRecommendations(report);
        RecommendedItem top = recommendations.stream()
                .filter(item -> item.getCategory() == ProductCategory.TOP)
                .findFirst()
                .orElseThrow();
        String missingCategoryBody = objectMapper.writeValueAsString(Map.of(
                "selectedItems",
                List.of(Map.of(
                        "category",
                        "TOP",
                        "productId",
                        top.getProduct().getId()
                ))
        ));

        mockMvc.perform(put("/api/v1/analyses/{reportId}/save", report.getId())
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingCategoryBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANALYSIS400_2"));
        mockMvc.perform(put("/api/v1/analyses/{reportId}/save", report.getId())
                        .header("Authorization", bearer(otherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingCategoryBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS404_1"));
        mockMvc.perform(put("/api/v1/analyses/{reportId}/save", report.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingCategoryBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    private AnalysisReport createCurrentReport(Member member) {
        AnalysisReport report = createUnsavedReport(member);
        report.markRecommendationGenerated(
                report.getRecommendationInputRevision(),
                "SIMILARITY_V1",
                Instant.parse("2026-07-26T00:00:00Z")
        );
        return analysisReportRepository.save(report);
    }

    private AnalysisReport createUnsavedReport(Member member) {
        AnalysisReport report = AnalysisReport.create(
                member,
                "https://example.com/analysis-" + System.nanoTime() + ".jpg",
                70
        );
        Tag tag = tagRepository.findTop3ByOrderByIdAsc().stream()
                .findFirst()
                .orElseGet(() -> tagRepository.save(Tag.create("미니멀", TagType.DETAIL)));
        report.addAiSuggestedTag(tag);
        return analysisReportRepository.save(report);
    }

    private List<RecommendedItem> createRecommendations(AnalysisReport report) {
        Product top = productRepository.save(product(
                "top",
                ProductCategory.TOP,
                "오버핏 셔츠",
                new BigDecimal("28900")
        ));
        Product bottom = productRepository.save(product(
                "bottom",
                ProductCategory.BOTTOM,
                "와이드 슬랙스",
                new BigDecimal("34900")
        ));
        return recommendedItemRepository.saveAll(List.of(
                recommendation(report, top, ProductCategory.TOP),
                recommendation(report, bottom, ProductCategory.BOTTOM)
        ));
    }

    private Product product(
            String key,
            ProductCategory category,
            String name,
            BigDecimal price
    ) {
        return Product.createProviderProduct(
                "fixture",
                ProviderIdentityType.PROVIDER_KEY,
                "identity-" + key,
                "materialization-" + key,
                "external-" + key,
                null,
                "merchant-" + key,
                ProductStorageMode.SNAPSHOT,
                name,
                null,
                "판매처",
                category,
                "https://example.com/" + key + ".jpg",
                null,
                price,
                null,
                "KRW",
                Instant.parse("2026-07-26T00:00:00Z"),
                "https://example.com/products/" + key,
                null,
                ProductAvailability.AVAILABLE,
                Instant.parse("2026-07-27T00:00:00Z")
        );
    }

    private RecommendedItem recommendation(
            AnalysisReport report,
            Product product,
            ProductCategory category
    ) {
        return RecommendedItem.create(
                report,
                product,
                report.getRecommendationInputRevision(),
                1,
                category,
                new BigDecimal("90.00"),
                new BigDecimal("88.00"),
                "SIMILARITY_V1",
                List.of("TAG_MATCH")
        );
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
        savedAnalysisItemRepository.deleteAll();
        closetSaveRepository.deleteAll();
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

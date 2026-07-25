package com.fitback.backend.domain.recommendation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.product.repository.ProductRepository;
import com.fitback.backend.domain.recommendation.repository.RecommendedItemRepository;
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
class RecommendationControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private AnalysisReportRepository analysisReportRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private RecommendedItemRepository recommendedItemRepository;

    @BeforeEach
    void cleanBefore() {
        cleanDatabase();
    }

    @AfterEach
    void cleanAfter() {
        cleanDatabase();
    }

    @Test
    void generatesAndReplacesCurrentRecommendationSetWithEightGroups() throws Exception {
        String email = "recommendation-flow@fitback.com";
        String accessToken = signUpAndGetAccessToken(email);
        AnalysisReport report = report(email, "Fixture");

        mockMvc.perform(post(
                                "/api/v1/analyses/{reportId}/recommendations",
                                report.getId()
                        )
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200_1"))
                .andExpect(jsonPath("$.data.reportId").value(report.getId()))
                .andExpect(jsonPath("$.data.analysisTags[0]").value("Fixture"))
                .andExpect(jsonPath("$.data.scoreVersion").value("SIMILARITY_V1"))
                .andExpect(jsonPath("$.data.recommendationStatus").value("CURRENT"))
                .andExpect(jsonPath("$.data.recommendationGroups.length()").value(8))
                .andExpect(jsonPath(
                        "$.data.recommendationGroups[*].items.length()",
                        everyItem(lessThanOrEqualTo(10))
                ))
                .andExpect(jsonPath("$.data.recommendationGroups[0].category").value("OUTER"))
                .andExpect(jsonPath("$.data.recommendationGroups[0].items").isEmpty())
                .andExpect(jsonPath("$.data.recommendationGroups[1].category").value("TOP"))
                .andExpect(jsonPath("$.data.recommendationGroups[1].items[0].rank").value(1))
                .andExpect(jsonPath("$.data.recommendationGroups[1].items[0].name")
                        .value("Fixture Minimal Shirt"))
                .andExpect(jsonPath("$.data.recommendationGroups[1].items[0].similarityScore")
                        .value(91.00))
                .andExpect(jsonPath("$.data.recommendationGroups[7].category").value("OTHER"))
                .andExpect(jsonPath("$.data.partial").value(true))
                .andExpect(jsonPath("$.data.warnings[0]").value("MATERIALIZATION_SKIPPED"));

        assertThat(productRepository.count()).isEqualTo(3);
        assertThat(recommendedItemRepository.count()).isEqualTo(3);

        mockMvc.perform(post(
                                "/api/v1/analyses/{reportId}/recommendations",
                                report.getId()
                        )
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommendationStatus").value("CURRENT"));

        assertThat(productRepository.count()).isEqualTo(3);
        assertThat(recommendedItemRepository.count()).isEqualTo(3);

        mockMvc.perform(get("/api/v1/analyses/{reportId}", report.getId())
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tags[0]").value("Fixture"))
                .andExpect(jsonPath("$.data.matchPercentage").value(70))
                .andExpect(jsonPath("$.data.recommendationStatus").value("CURRENT"))
                .andExpect(jsonPath("$.data.scoreVersion").value("SIMILARITY_V1"))
                .andExpect(jsonPath("$.data.recommendationGroups.length()").value(8));
    }

    @Test
    void rejectsReportWithoutDisplayTagsAndDoesNotCreateCurrentSet() throws Exception {
        String email = "recommendation-not-ready@fitback.com";
        String accessToken = signUpAndGetAccessToken(email);
        AnalysisReport report = report(email);

        mockMvc.perform(post(
                                "/api/v1/analyses/{reportId}/recommendations",
                                report.getId()
                        )
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANALYSIS409_1"));

        assertThat(recommendedItemRepository.count()).isZero();
        AnalysisReport persisted = analysisReportRepository.findById(report.getId()).orElseThrow();
        assertThat(persisted.getRecommendationGeneratedAt()).isNull();
    }

    @Test
    void removesLegacyPatchContractAndRequiresAuthentication() throws Exception {
        String email = "recommendation-contract@fitback.com";
        String accessToken = signUpAndGetAccessToken(email);
        AnalysisReport report = report(email, "Fixture");

        mockMvc.perform(patch(
                                "/api/v1/analyses/{reportId}/recommendations",
                                report.getId()
                        )
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("COMMON405_1"));

        mockMvc.perform(post(
                        "/api/v1/analyses/{reportId}/recommendations",
                        report.getId()
                ))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    @Test
    void hidesAnotherMembersReportAsNotFound() throws Exception {
        String ownerEmail = "recommendation-owner@fitback.com";
        signUpAndGetAccessToken(ownerEmail);
        AnalysisReport report = report(ownerEmail, "Fixture");
        String otherAccessToken = signUpAndGetAccessToken(
                "recommendation-other@fitback.com"
        );

        mockMvc.perform(post(
                                "/api/v1/analyses/{reportId}/recommendations",
                                report.getId()
                        )
                        .header("Authorization", bearer(otherAccessToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS404_1"));

        assertThat(recommendedItemRepository.count()).isZero();
    }

    private AnalysisReport report(String email, String... tagNames) {
        Member member = memberRepository.findByEmail(email).orElseThrow();
        AnalysisReport report = AnalysisReport.create(
                member,
                "https://example.com/analysis.jpg",
                70
        );
        for (String tagName : tagNames) {
            Tag tag = tagRepository.save(Tag.create(tagName, TagType.DETAIL));
            report.addAiSuggestedTag(tag);
        }
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

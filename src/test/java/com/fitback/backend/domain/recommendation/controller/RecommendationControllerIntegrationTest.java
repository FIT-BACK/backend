package com.fitback.backend.domain.recommendation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import com.fitback.backend.external.aitag.GarmentPiece;
import com.fitback.backend.global.observability.RecommendationPerformanceTrace;
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
                .andExpect(jsonPath("$.data.scoreVersion").value("IMAGE_TAG_WEIGHTED_V1"))
                .andExpect(jsonPath("$.data.recommendationStatus").value("CURRENT"))
                .andExpect(jsonPath("$.data.browserReranking.category").value("TOP"))
                .andExpect(jsonPath("$.data.browserReranking.candidates.length()").value(1))
                .andExpect(jsonPath("$.data.browserReranking.candidates[0].candidateId").isString())
                .andExpect(jsonPath("$.data.browserReranking.candidates[0].imageUrl").isString())
                .andExpect(jsonPath("$.data.browserReranking.candidates[0].tagSimilarity").value(1.0))
                .andExpect(jsonPath("$.data.browserReranking.candidates[0].name")
                        .value("Fixture Minimal Shirt"))
                .andExpect(jsonPath("$.data.browserReranking.candidates[0].sellerName")
                        .value("Fixture Store"))
                .andExpect(jsonPath("$.data.browserReranking.candidates[0].price.amount")
                        .value(70000.00))
                .andExpect(jsonPath("$.data.browserReranking.candidates[0].price.currency")
                        .value("KRW"))
                .andExpect(jsonPath("$.data.browserReranking.candidates[0].purchaseUrl")
                        .value("https://fixture.example/products/top-001"))
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
                        .value(79.00))
                .andExpect(jsonPath(
                        "$.data.recommendationGroups[1].items[0].reasonCodes[0]"
                ).value("FULL_ATTRIBUTE_MATCH"))
                .andExpect(jsonPath(
                        "$.data.recommendationGroups[1].items[0].reasonCodes[1]"
                ).value("HIGH_SIMILARITY"))
                .andExpect(jsonPath("$.data.recommendationGroups[7].category").value("OTHER"))
                .andExpect(jsonPath("$.data.partial").value(false))
                .andExpect(jsonPath("$.data.warnings").isEmpty());

        assertThat(productRepository.count()).isEqualTo(1);
        assertThat(recommendedItemRepository.count()).isEqualTo(1);
        assertThat(recommendedItemRepository.findAll())
                .allSatisfy(item -> {
                    assertThat(item.getScoreVersion()).isEqualTo("IMAGE_TAG_WEIGHTED_V1");
                    assertThat(item.getSimilarityScore()).isEqualByComparingTo("79.00");
                    assertThat(item.getFinalScore()).isEqualByComparingTo("79.00");
                });
        assertThat(analysisReportRepository.findById(report.getId()).orElseThrow()
                .getResultScoreVersion()).isEqualTo("IMAGE_TAG_WEIGHTED_V1");

        mockMvc.perform(post(
                                "/api/v1/analyses/{reportId}/recommendations",
                                report.getId()
                        )
                        .header("Authorization", bearer(accessToken))
                        .header(
                                RecommendationPerformanceTrace.REQUEST_HEADER,
                                RecommendationPerformanceTrace.REQUEST_VALUE
                        ))
                .andExpect(status().isOk())
                .andExpect(header().exists(RecommendationPerformanceTrace.RESPONSE_HEADER))
                .andExpect(jsonPath("$.data.recommendationStatus").value("CURRENT"));

        assertThat(productRepository.count()).isEqualTo(1);
        assertThat(recommendedItemRepository.count()).isEqualTo(1);

        mockMvc.perform(get("/api/v1/analyses/{reportId}", report.getId())
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tags[0]").value("Fixture"))
                .andExpect(jsonPath("$.data.matchPercentage").value(70))
                .andExpect(jsonPath("$.data.recommendationStatus").value("CURRENT"))
                .andExpect(jsonPath("$.data.scoreVersion").value("IMAGE_TAG_WEIGHTED_V1"))
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
    void rejectsLegacyReportWithoutGarmentCategory() throws Exception {
        String email = "recommendation-legacy-category@fitback.com";
        String accessToken = signUpAndGetAccessToken(email);
        AnalysisReport report = legacyReport(email, "Fixture");

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
    void generatesWithConfirmedKnownCustomTagsAndThreshold() throws Exception {
        String email = "recommendation-input@fitback.com";
        String accessToken = signUpAndGetAccessToken(email);
        AnalysisReport report = report(email, "Fixture");
        Long tagId = report.getDisplayTags().getFirst().getId();

        mockMvc.perform(post(
                                "/api/v1/analyses/{reportId}/recommendations",
                                report.getId()
                        )
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "confirmedTagIds", List.of(tagId),
                                "customTagNames", List.of(" 고프코어 ", "고프코어"),
                                "matchPercentage", 70
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisTags.length()").value(2))
                .andExpect(jsonPath("$.data.analysisTags[0]").value("Fixture"))
                .andExpect(jsonPath("$.data.analysisTags[1]").value("고프코어"))
                .andExpect(jsonPath("$.data.matchPercentage").value(70))
                .andExpect(jsonPath("$.data.scoreVersion")
                        .value("IMAGE_TAG_WEIGHTED_THR_V1"));
    }

    @Test
    void persistsAndReturnsNoAttributeMatchReason() throws Exception {
        String email = "recommendation-empty-reasons@fitback.com";
        String accessToken = signUpAndGetAccessToken(email);
        AnalysisReport report = report(email, "Unmatched");
        Long tagId = report.getDisplayTags().getFirst().getId();

        mockMvc.perform(post(
                                "/api/v1/analyses/{reportId}/recommendations",
                                report.getId()
                        )
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "confirmedTagIds", List.of(tagId),
                                "customTagNames", List.of("Fixture"),
                                "matchPercentage", 0
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scoreVersion")
                        .value("IMAGE_TAG_WEIGHTED_THR_V1"))
                .andExpect(jsonPath(
                        "$.data.recommendationGroups[1].items[0].similarityScore"
                ).value(49.00))
                .andExpect(jsonPath(
                        "$.data.recommendationGroups[1].items[0].reasonCodes.length()"
                ).value(1))
                .andExpect(jsonPath(
                        "$.data.recommendationGroups[1].items[0].reasonCodes[0]"
                ).value("NO_ATTRIBUTE_MATCH"));

        assertThat(recommendedItemRepository.findAll())
                .isNotEmpty()
                .allSatisfy(item -> {
                    assertThat(item.getScoreVersion())
                            .isEqualTo("IMAGE_TAG_WEIGHTED_THR_V1");
                    assertThat(item.getReasonCodeList())
                            .containsExactly("NO_ATTRIBUTE_MATCH");
                });
        assertThat(analysisReportRepository.findById(report.getId()).orElseThrow()
                .getResultScoreVersion()).isEqualTo("IMAGE_TAG_WEIGHTED_THR_V1");
    }

    @Test
    void rejectsRecommendationInputWithoutAnyTags() throws Exception {
        String email = "recommendation-empty-input@fitback.com";
        String accessToken = signUpAndGetAccessToken(email);
        AnalysisReport report = report(email, "Fixture");

        mockMvc.perform(post(
                                "/api/v1/analyses/{reportId}/recommendations",
                                report.getId()
                        )
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "confirmedTagIds", List.of(),
                                "customTagNames", List.of(),
                                "matchPercentage", 70
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    @Test
    void rejectsMoreThanEightCombinedTags() throws Exception {
        String email = "recommendation-too-many-tags@fitback.com";
        String accessToken = signUpAndGetAccessToken(email);
        AnalysisReport report = report(email, "Fixture");

        mockMvc.perform(post(
                                "/api/v1/analyses/{reportId}/recommendations",
                                report.getId()
                        )
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "confirmedTagIds", List.of(),
                                "customTagNames", List.of(
                                        "태그1", "태그2", "태그3", "태그4", "태그5",
                                        "태그6", "태그7", "태그8", "태그9"
                                ),
                                "matchPercentage", 70
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    @Test
    void rejectsMatchPercentageOutsideRange() throws Exception {
        String email = "recommendation-invalid-threshold@fitback.com";
        String accessToken = signUpAndGetAccessToken(email);
        AnalysisReport report = report(email, "Fixture");
        Long tagId = report.getDisplayTags().getFirst().getId();

        mockMvc.perform(post(
                                "/api/v1/analyses/{reportId}/recommendations",
                                report.getId()
                        )
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "confirmedTagIds", List.of(tagId),
                                "customTagNames", List.of(),
                                "matchPercentage", 101
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));

        mockMvc.perform(post(
                                "/api/v1/analyses/{reportId}/recommendations",
                                report.getId()
                        )
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "confirmedTagIds", List.of(tagId),
                                "customTagNames", List.of(),
                                "matchPercentage", -1
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    @Test
    void rejectsInvalidCustomTagNames() throws Exception {
        String email = "recommendation-invalid-custom-tag@fitback.com";
        String accessToken = signUpAndGetAccessToken(email);
        AnalysisReport report = report(email, "Fixture");

        for (String invalidName : List.of("   ", "a".repeat(51))) {
            mockMvc.perform(post(
                                    "/api/v1/analyses/{reportId}/recommendations",
                                    report.getId()
                            )
                            .header("Authorization", bearer(accessToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "confirmedTagIds", List.of(),
                                    "customTagNames", List.of(invalidName),
                                    "matchPercentage", 70
                            ))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON400_2"));
        }
    }

    @Test
    void recordsCurrentEmptySetWhenThresholdExcludesAllCandidates() throws Exception {
        String email = "recommendation-threshold@fitback.com";
        String accessToken = signUpAndGetAccessToken(email);
        AnalysisReport report = report(email, "Fixture", "Unmatched");
        List<Long> tagIds = report.getDisplayTags().stream().map(Tag::getId).toList();

        mockMvc.perform(post(
                                "/api/v1/analyses/{reportId}/recommendations",
                                report.getId()
                        )
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "confirmedTagIds", tagIds,
                                "customTagNames", List.of(),
                                "matchPercentage", 100
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommendationStatus").value("CURRENT"))
                .andExpect(jsonPath("$.data.scoreVersion")
                        .value("IMAGE_TAG_WEIGHTED_THR_V1"));

        assertThat(recommendedItemRepository.count()).isZero();
        assertThat(analysisReportRepository.findById(report.getId()).orElseThrow()
                .getResultScoreVersion()).isEqualTo("IMAGE_TAG_WEIGHTED_THR_V1");
    }

    @Test
    void rejectsUnknownConfirmedTag() throws Exception {
        String email = "recommendation-unknown-tag@fitback.com";
        String accessToken = signUpAndGetAccessToken(email);
        AnalysisReport report = report(email, "Fixture");

        mockMvc.perform(post(
                                "/api/v1/analyses/{reportId}/recommendations",
                                report.getId()
                        )
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "confirmedTagIds", List.of(999999L),
                                "customTagNames", List.of(),
                                "matchPercentage", 70
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TAG404_1"));
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
        return report(email, GarmentPiece.TOP, tagNames);
    }

    private AnalysisReport legacyReport(String email, String... tagNames) {
        return report(email, null, tagNames);
    }

    private AnalysisReport report(
            String email,
            GarmentPiece garmentPiece,
            String... tagNames
    ) {
        Member member = memberRepository.findByEmail(email).orElseThrow();
        AnalysisReport report = AnalysisReport.create(
                member,
                "https://example.com/analysis.jpg",
                70,
                garmentPiece
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

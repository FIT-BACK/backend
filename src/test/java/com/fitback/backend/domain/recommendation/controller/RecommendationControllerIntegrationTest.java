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
                .andExpect(jsonPath("$.data.scoreVersion").value("TAG_MATCH_RATIO_V1"))
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
                        .value(100.00))
                .andExpect(jsonPath(
                        "$.data.recommendationGroups[1].items[0].reasonCodes[0]"
                ).value("FULL_ATTRIBUTE_MATCH"))
                .andExpect(jsonPath(
                        "$.data.recommendationGroups[1].items[0].reasonCodes[1]"
                ).value("HIGH_SIMILARITY"))
                .andExpect(jsonPath("$.data.recommendationGroups[7].category").value("OTHER"))
                .andExpect(jsonPath("$.data.partial").value(true))
                .andExpect(jsonPath("$.data.warnings[0]").value("MATERIALIZATION_SKIPPED"));

        assertThat(productRepository.count()).isEqualTo(1);
        assertThat(recommendedItemRepository.count()).isEqualTo(1);
        assertThat(recommendedItemRepository.findAll())
                .allSatisfy(item -> {
                    assertThat(item.getScoreVersion()).isEqualTo("TAG_MATCH_RATIO_V1");
                    assertThat(item.getSimilarityScore()).isEqualByComparingTo("100.00");
                });
        assertThat(analysisReportRepository.findById(report.getId()).orElseThrow()
                .getResultScoreVersion()).isEqualTo("TAG_MATCH_RATIO_V1");

        mockMvc.perform(post(
                                "/api/v1/analyses/{reportId}/recommendations",
                                report.getId()
                        )
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommendationStatus").value("CURRENT"));

        assertThat(productRepository.count()).isEqualTo(1);
        assertThat(recommendedItemRepository.count()).isEqualTo(1);

        mockMvc.perform(get("/api/v1/analyses/{reportId}", report.getId())
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tags[0]").value("Fixture"))
                .andExpect(jsonPath("$.data.matchPercentage").value(70))
                .andExpect(jsonPath("$.data.recommendationStatus").value("CURRENT"))
                .andExpect(jsonPath("$.data.scoreVersion").value("TAG_MATCH_RATIO_V1"))
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
                        .value("TAG_MATCH_RATIO_THRESHOLD_V1"));
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
                        .value("TAG_MATCH_RATIO_THRESHOLD_V1"))
                .andExpect(jsonPath(
                        "$.data.recommendationGroups[1].items[0].similarityScore"
                ).value(0.00))
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
                            .isEqualTo("TAG_MATCH_RATIO_THRESHOLD_V1");
                    assertThat(item.getReasonCodeList())
                            .containsExactly("NO_ATTRIBUTE_MATCH");
                });
        assertThat(analysisReportRepository.findById(report.getId()).orElseThrow()
                .getResultScoreVersion()).isEqualTo("TAG_MATCH_RATIO_THRESHOLD_V1");
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
                        .value("TAG_MATCH_RATIO_THRESHOLD_V1"));

        assertThat(recommendedItemRepository.count()).isZero();
        assertThat(analysisReportRepository.findById(report.getId()).orElseThrow()
                .getResultScoreVersion()).isEqualTo("TAG_MATCH_RATIO_THRESHOLD_V1");
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

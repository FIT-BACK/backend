package com.fitback.backend.domain.lookbook.controller;

import static com.fitback.backend.domain.lookbook.LookbookImageFixtures.readyImage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImagePurpose;
import com.fitback.backend.domain.image.repository.ImageRepository;
import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookTagRepository;
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
class AnalysisLookbookLinkIntegrationTest {

    private static final String TEST_EMAIL = "analysis-lookbook@fitback.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LookbookTagRepository lookbookTagRepository;

    @Autowired
    private LookbookRepository lookbookRepository;

    @Autowired
    private RecommendedItemRepository recommendedItemRepository;

    @Autowired
    private AnalysisReportRepository analysisReportRepository;

    @Autowired
    private ImageRepository imageRepository;

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
    void createsLookbookFromOwnedAnalysisImageAndRecommendationProduct() throws Exception {
        String accessToken = signUpAndGetAccessToken(TEST_EMAIL);
        Member member = memberRepository.findByEmail(TEST_EMAIL).orElseThrow();
        Image originalImage = imageRepository.save(readyImage(
                "analysis-original",
                member,
                ImagePurpose.ANALYSIS_ORIGINAL
        ));
        AnalysisReport report = analysisReportRepository.save(
                AnalysisReport.create(member, originalImage, 70)
        );
        report.markRecommendationGenerated(
                report.getRecommendationInputRevision(),
                "SIMILARITY_V1",
                Instant.parse("2026-07-26T00:00:00Z")
        );
        report = analysisReportRepository.save(report);
        Product product = productRepository.save(product());
        recommendedItemRepository.save(RecommendedItem.create(
                report,
                product,
                report.getRecommendationInputRevision(),
                1,
                ProductCategory.TOP,
                new BigDecimal("90.00"),
                new BigDecimal("88.00"),
                "SIMILARITY_V1",
                List.of("TAG_MATCH")
        ));
        Tag tag = tagRepository.save(Tag.create("미니멀", TagType.DETAIL));
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "originalImageId",
                originalImage.getId(),
                "matchedProductId",
                product.getId(),
                "sourceReportId",
                report.getId(),
                "tagIds",
                List.of(tag.getId()),
                "comment",
                "분석 결과로 완성한 룩"
        ));

        mockMvc.perform(get("/api/v1/analyses/{reportId}", report.getId())
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalImageId").value(originalImage.getId()))
                .andExpect(jsonPath("$.data.recommendationGroups[*].items[*].productId")
                        .value(hasItem(product.getId().intValue())));

        String response = mockMvc.perform(post("/api/v1/lookbooks")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("COMMON201_1"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long lookbookId = objectMapper.readTree(response).at("/data/lookbookId").asLong();

        Lookbook lookbook = lookbookRepository
                .findByIdAndDeletedAtIsNull(lookbookId)
                .orElseThrow();
        assertThat(lookbook.getMatchedImage()).isNull();
        assertThat(lookbook.getMatchedProduct().getId()).isEqualTo(product.getId());
        assertThat(lookbook.getMatchedProductImageUrl()).isEqualTo(product.getImageUrl());
        assertThat(lookbook.getPurchaseUrl()).isEqualTo(product.getPurchaseUrl());

        mockMvc.perform(get("/api/v1/lookbooks/{lookbookId}", lookbookId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchedProductId").value(product.getId()))
                .andExpect(jsonPath("$.data.matchedImageUrl").value(product.getImageUrl()))
                .andExpect(jsonPath("$.data.originalImageUrl").isNotEmpty());
    }

    private Product product() {
        return Product.createProviderProduct(
                "fixture",
                ProviderIdentityType.PROVIDER_KEY,
                "lookbook-product",
                "lookbook-materialization",
                "lookbook-external",
                null,
                "lookbook-merchant",
                ProductStorageMode.SNAPSHOT,
                "오버핏 셔츠",
                null,
                "판매처",
                ProductCategory.TOP,
                "https://example.com/product.jpg",
                null,
                new BigDecimal("28900"),
                null,
                "KRW",
                Instant.parse("2026-07-26T00:00:00Z"),
                "https://example.com/products/top",
                null,
                ProductAvailability.AVAILABLE,
                Instant.parse("2026-07-27T00:00:00Z")
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
        lookbookTagRepository.deleteAll();
        lookbookRepository.deleteAll();
        recommendedItemRepository.deleteAll();
        analysisReportRepository.deleteAll();
        imageRepository.deleteAll();
        productRepository.deleteAll();
        tagRepository.deleteAll();
        memberRepository.findByEmail(TEST_EMAIL).ifPresent(memberRepository::delete);
    }

    private static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}

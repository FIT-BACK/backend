package com.fitback.backend.domain.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitback.backend.domain.product.repository.ProductRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
@Transactional
class ProductControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void searchesWithoutWritingAndMaterializesThenReadsDetail() throws Exception {
        String accessToken = signUpAndGetAccessToken("product-flow@fitback.com");

        MvcResult searchResult = mockMvc.perform(get("/api/v1/products")
                        .header("Authorization", bearer(accessToken))
                        .param("keyword", "Fixture")
                        .param("category", "TOP")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200_1"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].productId").isEmpty())
                .andExpect(jsonPath("$.data.items[0].candidateToken").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].category").value("TOP"))
                .andExpect(jsonPath("$.data.items[0].price.amount").value(70000.00))
                .andExpect(jsonPath("$.data.items[0].price.currency").value("KRW"))
                .andExpect(jsonPath("$.data.items[0].price.type").value("SALE"))
                .andExpect(jsonPath("$.data.items[0].detailSupported").value(true))
                .andExpect(jsonPath("$.data.items[0].saveSupported").value(true))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.partial").value(false))
                .andReturn();

        assertThat(productRepository.count()).isZero();
        String candidateToken = objectMapper.readTree(
                searchResult.getResponse().getContentAsString()
        ).at("/data/items/0/candidateToken").asText();

        MvcResult materializeResult = materialize(accessToken, candidateToken, true)
                .andReturn();
        JsonNode materialized = objectMapper.readTree(
                materializeResult.getResponse().getContentAsString()
        ).get("data");
        long productId = materialized.get("productId").asLong();
        assertThat(productRepository.count()).isEqualTo(1);

        materialize(accessToken, candidateToken, false)
                .andExpect(jsonPath("$.data.productId").value(productId));
        assertThat(productRepository.count()).isEqualTo(1);

        mockMvc.perform(get("/api/v1/products/{productId}", productId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(productId))
                .andExpect(jsonPath("$.data.name").value("Fixture Minimal Shirt"))
                .andExpect(jsonPath("$.data.category").value("TOP"))
                .andExpect(jsonPath("$.data.purchaseUrl")
                        .value("https://fixture.example/products/top-001"))
                .andExpect(jsonPath("$.data.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.dataStatus").value("LIVE"))
                .andExpect(jsonPath("$.data.tags").isEmpty())
                .andExpect(jsonPath("$.data.isSaved").value(false));
    }

    @Test
    void doesNotIssueCandidateTokenForUnstableIdentity() throws Exception {
        String accessToken = signUpAndGetAccessToken("product-unstable@fitback.com");

        mockMvc.perform(get("/api/v1/products")
                        .header("Authorization", bearer(accessToken))
                        .param("keyword", "Unstable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].candidateToken").isEmpty())
                .andExpect(jsonPath("$.data.items[0].detailSupported").value(false))
                .andExpect(jsonPath("$.data.items[0].saveSupported").value(false));
    }

    @Test
    void rejectsCandidateTokenUsedByAnotherMember() throws Exception {
        String ownerToken = signUpAndGetAccessToken("product-owner@fitback.com");
        String otherToken = signUpAndGetAccessToken("product-other@fitback.com");
        String candidateToken = searchCandidateToken(ownerToken);

        mockMvc.perform(post("/api/v1/product-references")
                        .header("Authorization", bearer(otherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "candidateToken",
                                candidateToken
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT422_1"));
    }

    @Test
    void validatesSearchQueryAndRequiresAuthentication() throws Exception {
        String accessToken = signUpAndGetAccessToken("product-validation@fitback.com");

        mockMvc.perform(get("/api/v1/products")
                        .header("Authorization", bearer(accessToken))
                        .param("keyword", " ")
                        .param("pageSize", "21"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));

        mockMvc.perform(get("/api/v1/products")
                        .header("Authorization", bearer(accessToken))
                        .param("keyword", "Fixture")
                        .param("category", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));

        mockMvc.perform(get("/api/v1/products")
                        .header("Authorization", bearer(accessToken))
                        .param("keyword", "Fixture")
                        .param("pageSize", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));

        mockMvc.perform(get("/api/v1/products/not-a-number")
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));

        mockMvc.perform(get("/api/v1/products").param("keyword", "Fixture"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    @Test
    void rejectsOversizedCandidateTokenBeforeVerification() throws Exception {
        String accessToken = signUpAndGetAccessToken("product-token-size@fitback.com");

        mockMvc.perform(post("/api/v1/product-references")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "candidateToken",
                                "x".repeat(4097)
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    @Test
    void returnsProductNotFoundForUnknownInternalId() throws Exception {
        String accessToken = signUpAndGetAccessToken("product-not-found@fitback.com");

        mockMvc.perform(get("/api/v1/products/{productId}", 999999L)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT404_1"));
    }

    private org.springframework.test.web.servlet.ResultActions materialize(
            String accessToken,
            String candidateToken,
            boolean created
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/product-references")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "candidateToken",
                                candidateToken
                        ))))
                .andExpect(created ? status().isCreated() : status().isOk())
                .andExpect(jsonPath("$.code").value(
                        created ? "COMMON201_1" : "COMMON200_1"
                ))
                .andExpect(jsonPath("$.data.created").value(created))
                .andExpect(jsonPath("$.data.availability").value("AVAILABLE"));
    }

    private String searchCandidateToken(String accessToken) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/products")
                        .header("Authorization", bearer(accessToken))
                        .param("keyword", "Minimal"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/items/0/candidateToken")
                .asText();
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
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).at("/data/accessToken").asText();
    }

    private static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}

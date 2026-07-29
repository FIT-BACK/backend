package com.fitback.backend.external.shopping.shopify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.repository.ProductRepository;
import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductStorageMode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(
        webEnvironment = WebEnvironment.MOCK,
        properties = {
            "shopping.provider=shopify",
            "shopping.shopify.enabled=true"
        }
)
@Transactional
class ShopifyProductFlowIntegrationTest {

    private static final String PRODUCT_ID = "gid://shopify/Product/product-73";
    private static final String VARIANT_ID =
            "gid://shopify/ProductVariant/variant-73";
    private static final String MERCHANT_ID = "gid://shopify/Shop/shop-73";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @MockitoBean
    private ShopifyGlobalCatalogClient shopifyClient;

    private ShopifyCatalogItem catalogItem;

    @BeforeEach
    void setUpProvider() {
        catalogItem = new ShopifyCatalogItem(
                PRODUCT_ID,
                VARIANT_ID,
                "Shopify Black Hoodie",
                "Apparel > Hoodies",
                "https://cdn.example/hoodie.jpg",
                new BigDecimal("73.00"),
                "USD",
                true,
                MERCHANT_ID,
                "Example Shop",
                "https://merchant.example/products/hoodie"
        );
        when(shopifyClient.search(
                anyString(),
                nullable(String.class),
                anyInt()
        )).thenReturn(new ShopifyCatalogPage(List.of(catalogItem), null));
        when(shopifyClient.lookup(PRODUCT_ID, VARIANT_ID))
                .thenReturn(Optional.of(catalogItem));
    }

    @Test
    void materializesShopifyCandidateAndRefreshesDetailFromLiveLookup() throws Exception {
        String accessToken = signUpAndGetAccessToken();

        MvcResult searchResult = mockMvc.perform(get("/api/v1/products")
                        .header("Authorization", bearer(accessToken))
                        .param("keyword", "hoodie")
                        .param("category", "TOP")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].productId").isEmpty())
                .andExpect(jsonPath("$.data.items[0].candidateToken").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].name")
                        .value("Shopify Black Hoodie"))
                .andExpect(jsonPath("$.data.items[0].category").value("TOP"))
                .andExpect(jsonPath("$.data.items[0].price.amount").value(73.00))
                .andExpect(jsonPath("$.data.items[0].price.currency").value("USD"))
                .andExpect(jsonPath("$.data.items[0].saveSupported").value(true))
                .andReturn();

        String candidateToken = objectMapper.readTree(
                searchResult.getResponse().getContentAsString()
        ).at("/data/items/0/candidateToken").asText();

        MvcResult materializeResult = mockMvc.perform(post("/api/v1/product-references")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "candidateToken",
                                candidateToken
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.created").value(true))
                .andReturn();

        long productId = objectMapper.readTree(
                materializeResult.getResponse().getContentAsString()
        ).at("/data/productId").asLong();
        Product stored = productRepository.findById(productId).orElseThrow();
        assertThat(stored.getSourceApi()).isEqualTo("shopify");
        assertThat(stored.getExternalProductId()).isEqualTo(PRODUCT_ID);
        assertThat(stored.getExternalVariantId()).isEqualTo(VARIANT_ID);
        assertThat(stored.getMerchantId()).isEqualTo(MERCHANT_ID);
        assertThat(stored.getStorageMode()).isEqualTo(ProductStorageMode.IDENTITY_ONLY);
        assertThat(stored.getAvailability()).isEqualTo(ProductAvailability.UNKNOWN);
        assertThat(stored.getName()).isNull();
        assertThat(stored.getCurrentPrice()).isNull();
        assertThat(stored.getCurrency()).isNull();
        assertThat(stored.getImageUrl()).isNull();
        assertThat(stored.getPurchaseUrl()).isNull();
        assertThat(stored.getSnapshotExpiresAt()).isNull();

        mockMvc.perform(post("/api/v1/product-references")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "candidateToken",
                                candidateToken
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(productId))
                .andExpect(jsonPath("$.data.created").value(false));

        mockMvc.perform(get("/api/v1/products/{productId}", productId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(productId))
                .andExpect(jsonPath("$.data.name").value("Shopify Black Hoodie"))
                .andExpect(jsonPath("$.data.price.amount").value(73.00))
                .andExpect(jsonPath("$.data.price.currency").value("USD"))
                .andExpect(jsonPath("$.data.dataStatus").value("LIVE"));

        Product afterDetail = productRepository.findById(productId).orElseThrow();
        assertThat(afterDetail.getStorageMode()).isEqualTo(ProductStorageMode.IDENTITY_ONLY);
        assertThat(afterDetail.getName()).isNull();
        assertThat(afterDetail.getCurrentPrice()).isNull();
        assertThat(afterDetail.getImageUrl()).isNull();
        assertThat(afterDetail.getPurchaseUrl()).isNull();

        verify(shopifyClient).search("hoodie shirt top", null, 10);
        verify(shopifyClient, times(2)).lookup(PRODUCT_ID, VARIANT_ID);
    }

    private String signUpAndGetAccessToken() throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email",
                                "shopify-product-flow@fitback.com",
                                "password",
                                "password123"
                        ))))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode data = objectMapper.readTree(response).get("data");
        return data.get("accessToken").asText();
    }

    private static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}

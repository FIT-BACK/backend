package com.fitback.backend.domain.image.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitback.backend.domain.image.repository.ImageRepository;
import com.fitback.backend.domain.image.service.port.ImageUploadUrl;
import com.fitback.backend.domain.image.service.port.ImageUploadUrlPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
@Import(ImageControllerIntegrationTest.UploadUrlTestConfig.class)
@Transactional
class ImageControllerIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ImageRepository imageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void authenticatedMemberCreatesPresignedUpload() throws Exception {
        String accessToken = signUpAndGetAccessToken("image-api@fitback.com");

        mockMvc.perform(post("/api/v1/images/upload-requests")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "purpose": "ANALYSIS",
                                  "contentType": "image/jpeg",
                                  "fileSize": 3145728
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON201_1"))
                .andExpect(jsonPath("$.data.imageId").isNotEmpty())
                .andExpect(jsonPath("$.data.uploadUrl").value("https://s3.example/upload"))
                .andExpect(jsonPath("$.data.uploadMethod").value("POST"))
                .andExpect(jsonPath("$.data.uploadFields['Content-Type']").value("image/jpeg"))
                .andExpect(jsonPath("$.data.uploadFields.policy").value("encoded-policy"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-07-24T00:05:00Z"))
                .andExpect(jsonPath("$.data.requiredHeaders").doesNotExist())
                .andExpect(jsonPath("$.data.imageUrl").doesNotExist());

        assertThat(imageRepository.count()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT purpose FROM image",
                String.class
        )).isEqualTo("ANALYSIS_ORIGINAL");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM image",
                String.class
        )).isEqualTo("PENDING");
    }

    @Test
    void rejectsUnsupportedImageContentType() throws Exception {
        String accessToken = signUpAndGetAccessToken("image-type@fitback.com");

        mockMvc.perform(post("/api/v1/images/upload-requests")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "purpose": "PROFILE",
                                  "contentType": "image/gif",
                                  "fileSize": 1024
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE400_1"));
    }

    @Test
    void rejectsFileLargerThanFiveMegabytes() throws Exception {
        String accessToken = signUpAndGetAccessToken("image-size@fitback.com");

        mockMvc.perform(post("/api/v1/images/upload-requests")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "purpose": "PROFILE",
                                  "contentType": "image/png",
                                  "fileSize": 5242881
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_2"));
    }

    @Test
    void rejectsUnknownImagePurpose() throws Exception {
        String accessToken = signUpAndGetAccessToken("image-purpose@fitback.com");

        mockMvc.perform(post("/api/v1/images/upload-requests")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "purpose": "UNKNOWN",
                                  "contentType": "image/png",
                                  "fileSize": 1024
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_1"));
    }

    @Test
    void rejectsLegacyPersistencePurposeFromPublicApi() throws Exception {
        String accessToken = signUpAndGetAccessToken("legacy-image-purpose@fitback.com");

        mockMvc.perform(post("/api/v1/images/upload-requests")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "purpose": "LOOKBOOK_MATCHED",
                                  "contentType": "image/png",
                                  "fileSize": 1024
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_1"));
    }

    @Test
    void returnsImageErrorWhenPostPolicyGenerationFails() throws Exception {
        String accessToken = signUpAndGetAccessToken("image-policy-error@fitback.com");

        mockMvc.perform(post("/api/v1/images/upload-requests")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "purpose": "PROFILE",
                                  "contentType": "image/jpeg",
                                  "fileSize": 2048
                                }
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("IMAGE500_1"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/images/upload-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "purpose": "PROFILE",
                                  "contentType": "image/png",
                                  "fileSize": 1024
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON401_1"));
    }

    private String signUpAndGetAccessToken(String email) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "email", email,
                "password", "password123"
        ));
        String response = mockMvc.perform(post("/api/v1/auth/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode data = objectMapper.readTree(response).get("data");
        return data.get("accessToken").asText();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class UploadUrlTestConfig {

        @Bean
        @Primary
        ImageUploadUrlPort imageUploadUrlPort() {
            return (objectKey, contentType, fileSize, expiresAt) -> testUploadUrl(
                    objectKey,
                    contentType,
                    fileSize,
                    expiresAt
            );
        }

        @Bean
        @Primary
        Clock imageUploadClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        private ImageUploadUrl testUploadUrl(
                String objectKey,
                String contentType,
                long fileSize,
                Instant expiresAt
        ) {
            if (fileSize == 2048) {
                throw new IllegalStateException("test policy generation failure");
            }
            assertThat(fileSize).isEqualTo(3_145_728);
            assertThat(expiresAt).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
            return new ImageUploadUrl(
                    "https://s3.example/upload",
                    "POST",
                    Map.of(
                            "key", objectKey,
                            "Content-Type", contentType,
                            "policy", "encoded-policy"
                    )
            );
        }
    }
}

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
import java.time.Duration;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
@Import(ImageControllerIntegrationTest.UploadUrlTestConfig.class)
@Transactional
class ImageControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ImageRepository imageRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void authenticatedMemberCreatesPresignedUpload() throws Exception {
        String accessToken = signUpAndGetAccessToken("image-api@fitback.com");

        mockMvc.perform(post("/api/v1/images/presigned-uploads")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "purpose": "ANALYSIS_ORIGINAL",
                                  "contentType": "image/jpeg",
                                  "fileSize": 3145728
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON201_1"))
                .andExpect(jsonPath("$.data.imageId").isNotEmpty())
                .andExpect(jsonPath("$.data.uploadUrl").value("https://s3.example/upload"))
                .andExpect(jsonPath("$.data.uploadMethod").value("PUT"))
                .andExpect(jsonPath("$.data.requiredHeaders['Content-Type']").value("image/jpeg"))
                .andExpect(jsonPath("$.data.imageUrl").value(
                        org.hamcrest.Matchers.startsWith("https://cdn.example/")
                ));

        assertThat(imageRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectsUnsupportedImageContentType() throws Exception {
        String accessToken = signUpAndGetAccessToken("image-type@fitback.com");

        mockMvc.perform(post("/api/v1/images/presigned-uploads")
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

        mockMvc.perform(post("/api/v1/images/presigned-uploads")
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

        mockMvc.perform(post("/api/v1/images/presigned-uploads")
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
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/images/presigned-uploads")
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
            return (objectKey, contentType, fileSize, expiration) -> testUploadUrl(
                    objectKey,
                    contentType,
                    fileSize,
                    expiration
            );
        }

        private ImageUploadUrl testUploadUrl(
                String objectKey,
                String contentType,
                long fileSize,
                Duration expiration
        ) {
            assertThat(fileSize).isEqualTo(3_145_728);
            assertThat(expiration).isEqualTo(Duration.ofMinutes(5));
            return new ImageUploadUrl(
                    "https://s3.example/upload",
                    "PUT",
                    Map.of("Content-Type", contentType),
                    "https://cdn.example/" + objectKey
            );
        }
    }
}

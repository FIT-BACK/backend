package com.fitback.backend.domain.image.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.image.dto.ImageUploadRequest;
import com.fitback.backend.domain.image.dto.ImageUploadResponse;
import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImagePurpose;
import com.fitback.backend.domain.image.entity.ImageStatus;
import com.fitback.backend.domain.image.repository.ImageRepository;
import com.fitback.backend.domain.image.service.port.ImageUploadUrl;
import com.fitback.backend.domain.image.service.port.ImageUploadUrlPort;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImageUploadServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T05:00:00Z");

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private ImageUploadUrlPort imageUploadUrlPort;

    private ImageUploadService imageUploadService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        imageUploadService = new ImageUploadService(imageRepository, imageUploadUrlPort, clock);
    }

    @Test
    void createsPendingImageAndFiveMinuteUploadUrl() {
        Member owner = Member.create(
                "image@fitback.com",
                "image-user",
                "password",
                LoginProvider.EMAIL
        );
        ImageUploadRequest request = new ImageUploadRequest(
                ImagePurpose.ANALYSIS_ORIGINAL,
                "IMAGE/JPEG",
                3_145_728
        );
        when(imageUploadUrlPort.create(any(), any(), any())).thenAnswer(invocation -> {
            String objectKey = invocation.getArgument(0);
            return new ImageUploadUrl(
                    "https://s3.example/upload",
                    "PUT",
                    Map.of("Content-Type", "image/jpeg"),
                    "https://cdn.example/" + objectKey
            );
        });

        ImageUploadResponse response = imageUploadService.createUpload(owner, request);

        assertThat(response.uploadMethod()).isEqualTo("PUT");
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(response.requiredHeaders()).containsEntry("Content-Type", "image/jpeg");
        assertThat(response.imageUrl()).startsWith(
                "https://cdn.example/prod/images/analysis_original/2026/07/"
        );

        ArgumentCaptor<Image> imageCaptor = ArgumentCaptor.forClass(Image.class);
        verify(imageRepository).save(imageCaptor.capture());
        Image savedImage = imageCaptor.getValue();
        assertThat(savedImage.getId()).isEqualTo(response.imageId());
        assertThat(savedImage.getOwner()).isSameAs(owner);
        assertThat(savedImage.getObjectKey()).matches(
                "prod/images/analysis_original/2026/07/[0-9a-f-]{36}\\.jpg"
        );
        assertThat(savedImage.getStatus()).isEqualTo(ImageStatus.PENDING);
        assertThat(savedImage.getContentType()).isEqualTo("image/jpeg");
    }

    @Test
    void rejectsUnsupportedContentTypeBeforePersistence() {
        Member owner = Member.create(
                "invalid@fitback.com",
                "invalid-user",
                "password",
                LoginProvider.EMAIL
        );
        ImageUploadRequest request = new ImageUploadRequest(
                ImagePurpose.PROFILE,
                "image/gif",
                1024
        );

        assertThatThrownBy(() -> imageUploadService.createUpload(owner, request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.IMAGE_UNSUPPORTED_CONTENT_TYPE)
                );
    }
}

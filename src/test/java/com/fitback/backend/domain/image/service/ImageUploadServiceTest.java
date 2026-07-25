package com.fitback.backend.domain.image.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.image.dto.ImageCompleteResponse;
import com.fitback.backend.domain.image.dto.ImageUploadPurpose;
import com.fitback.backend.domain.image.dto.ImageUploadRequest;
import com.fitback.backend.domain.image.dto.ImageUploadResponse;
import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImagePurpose;
import com.fitback.backend.domain.image.entity.ImageStatus;
import com.fitback.backend.domain.image.entity.ImageVisibility;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ImageUploadServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T05:00:00Z");

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private ImageUploadUrlPort imageUploadUrlPort;

    @Mock
    private ImageObjectStorage imageObjectStorage;

    @Mock
    private ImageAccessUrlProvider imageAccessUrlProvider;

    @Mock
    private ImageUploadTransactionService imageUploadTransactionService;

    private ImageUploadService imageUploadService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        imageUploadService = new ImageUploadService(
                imageRepository,
                imageUploadUrlPort,
                imageObjectStorage,
                imageAccessUrlProvider,
                imageUploadTransactionService,
                clock
        );
    }

    @Test
    void createsPendingImageAndFiveMinuteUploadUrl() {
        Member owner = Member.create(
                "image@fitback.com",
                "image-user",
                "password",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(owner, "id", 42L);
        ImageUploadRequest request = new ImageUploadRequest(
                ImageUploadPurpose.ANALYSIS,
                "IMAGE/JPEG",
                3_145_728
        );
        when(imageUploadUrlPort.create(any(), any(), anyLong(), any())).thenAnswer(invocation -> {
            String objectKey = invocation.getArgument(0);
            return new ImageUploadUrl(
                    "https://s3.example/upload",
                    "POST",
                    Map.of(
                            "key", objectKey,
                            "Content-Type", "image/jpeg",
                            "policy", "encoded-policy"
                    )
            );
        });

        ImageUploadResponse response = imageUploadService.createUpload(owner, request);

        assertThat(response.uploadMethod()).isEqualTo("POST");
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(response.uploadFields())
                .containsEntry("Content-Type", "image/jpeg")
                .containsEntry("policy", "encoded-policy");

        ArgumentCaptor<Image> imageCaptor = ArgumentCaptor.forClass(Image.class);
        verify(imageRepository).save(imageCaptor.capture());
        Image savedImage = imageCaptor.getValue();
        assertThat(savedImage.getId()).isEqualTo(response.imageId());
        assertThat(savedImage.getOwner()).isSameAs(owner);
        assertThat(savedImage.getObjectKey()).matches(
                "images/analysis/42/2026/07/[0-9a-f-]{36}\\.jpg"
        );
        assertThat(response.uploadFields())
                .containsEntry("key", savedImage.getObjectKey());
        assertThat(savedImage.getPurpose()).isEqualTo(ImagePurpose.ANALYSIS_ORIGINAL);
        assertThat(savedImage.getStatus()).isEqualTo(ImageStatus.PENDING);
        assertThat(savedImage.getContentType()).isEqualTo("image/jpeg");
        verify(imageUploadUrlPort).create(
                any(),
                eq("image/jpeg"),
                eq(3_145_728L),
                eq(NOW.plus(Duration.ofMinutes(5)))
        );
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
                ImageUploadPurpose.PROFILE,
                "image/gif",
                1024
        );

        assertThatThrownBy(() -> imageUploadService.createUpload(owner, request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.IMAGE_UNSUPPORTED_CONTENT_TYPE)
                );
        verify(imageRepository, never()).save(any());
    }

    @Test
    void mapsUploadPolicyGenerationFailureToImageError() {
        Member owner = Member.create(
                "presign-error@fitback.com",
                "presign-error-user",
                "password",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(owner, "id", 42L);
        ImageUploadRequest request = new ImageUploadRequest(
                ImageUploadPurpose.PROFILE,
                "image/jpeg",
                1024
        );
        when(imageUploadUrlPort.create(any(), any(), anyLong(), any()))
                .thenThrow(new IllegalStateException("credential provider unavailable"));

        assertThatThrownBy(() -> imageUploadService.createUpload(owner, request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.IMAGE_PRESIGN_ERROR)
                );
        verify(imageRepository, never()).save(any());
    }

    @Test
    void reissuesPostFieldsForPendingUploadWithoutChangingObjectKey() {
        Member owner = Member.create(
                "reissue@fitback.com",
                "reissue-user",
                "password",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(owner, "id", 42L);
        Image image = Image.createPending(
                "image-id",
                owner,
                "images/profile/42/2026/07/image-id.jpg",
                ImagePurpose.PROFILE,
                "image/jpeg",
                1024,
                ImageVisibility.PRIVATE,
                NOW.plusSeconds(60)
        );
        when(imageRepository.findByIdAndOwnerId("image-id", 42L))
                .thenReturn(Optional.of(image));
        when(imageUploadUrlPort.create(
                eq(image.getObjectKey()),
                eq("image/jpeg"),
                eq(1024L),
                eq(NOW.plus(Duration.ofMinutes(5)))
        )).thenReturn(new ImageUploadUrl(
                "https://s3.example/upload",
                "POST",
                Map.of("key", image.getObjectKey(), "policy", "renewed-policy")
        ));

        ImageUploadResponse response = imageUploadService.reissueUpload(owner, "image-id");

        assertThat(response.uploadMethod()).isEqualTo("POST");
        assertThat(response.uploadFields())
                .containsEntry("key", image.getObjectKey())
                .containsEntry("policy", "renewed-policy");
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(image.getObjectKey())
                .isEqualTo("images/profile/42/2026/07/image-id.jpg");
        assertThat(image.getPresignedExpiresAt())
                .isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void reissuesFuturePendingUploadStatusDuringCompatibilityRelease() {
        Member owner = Member.create(
                "future-reissue@fitback.com",
                "future-reissue-user",
                "password",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(owner, "id", 42L);
        Image image = Image.createPending(
                "future-image-id",
                owner,
                "images/profile/42/2026/07/future-image-id.jpg",
                ImagePurpose.PROFILE,
                "image/jpeg",
                1024,
                ImageVisibility.PRIVATE,
                NOW.plusSeconds(60)
        );
        ReflectionTestUtils.setField(image, "status", ImageStatus.PENDING_UPLOAD);
        when(imageRepository.findByIdAndOwnerId("future-image-id", 42L))
                .thenReturn(Optional.of(image));
        when(imageUploadUrlPort.create(any(), any(), anyLong(), any()))
                .thenReturn(new ImageUploadUrl(
                        "https://s3.example/upload",
                        "POST",
                        Map.of("key", image.getObjectKey(), "policy", "future-policy")
                ));

        ImageUploadResponse response = imageUploadService.reissueUpload(
                owner,
                "future-image-id"
        );

        assertThat(response.uploadMethod()).isEqualTo("POST");
        assertThat(response.uploadFields()).containsEntry("policy", "future-policy");
    }

    @Test
    void inspectsStorageBetweenPendingValidationAndTransactionalCompletion() {
        Member owner = Member.create(
                "complete@fitback.com",
                "complete-user",
                "password",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(owner, "id", 7L);
        ImageObjectStorage.StoredImageObject storedObject =
                new ImageObjectStorage.StoredImageObject(
                        3,
                        "image/jpeg",
                        new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}
                );
        when(imageUploadTransactionService.getPendingUpload(7L, "image-id"))
                .thenReturn(new ImageUploadTransactionService.PendingUpload("object-key"));
        when(imageObjectStorage.inspect("object-key")).thenReturn(storedObject);
        when(imageUploadTransactionService.completeUpload(7L, "image-id", storedObject))
                .thenReturn(new ImageCompleteResponse("image-id", "READY"));

        ImageCompleteResponse response = imageUploadService.completeUpload(owner, "image-id");

        assertThat(response.status()).isEqualTo("READY");
        InOrder inOrder = inOrder(imageUploadTransactionService, imageObjectStorage);
        inOrder.verify(imageUploadTransactionService).getPendingUpload(7L, "image-id");
        inOrder.verify(imageObjectStorage).inspect("object-key");
        inOrder.verify(imageUploadTransactionService)
                .completeUpload(7L, "image-id", storedObject);
    }
}

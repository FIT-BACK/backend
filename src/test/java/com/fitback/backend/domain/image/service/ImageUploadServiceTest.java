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
        ImageUploadRequest request = new ImageUploadRequest(
                ImagePurpose.ANALYSIS_ORIGINAL,
                "IMAGE/JPEG",
                3_145_728
        );
        when(imageUploadUrlPort.create(any(), any(), anyLong(), any())).thenAnswer(invocation -> {
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
        verify(imageUploadUrlPort).create(
                any(),
                eq("image/jpeg"),
                eq(3_145_728L),
                eq(Duration.ofMinutes(5))
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
                ImagePurpose.PROFILE,
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

package com.fitback.backend.domain.image.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImagePurpose;
import com.fitback.backend.domain.image.entity.ImageStatus;
import com.fitback.backend.domain.image.entity.ImageVisibility;
import com.fitback.backend.domain.image.repository.ImageRepository;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ImageUploadTransactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T05:00:00Z");

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private ImageSignatureValidator imageSignatureValidator;

    private ImageUploadTransactionService service;

    @BeforeEach
    void setUp() {
        service = new ImageUploadTransactionService(
                imageRepository,
                imageSignatureValidator,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void rejectsImageWhenStoredSignatureDoesNotMatch() {
        Image image = pendingImage(NOW.plusSeconds(300));
        ReflectionTestUtils.setField(image, "status", ImageStatus.PENDING_UPLOAD);
        byte[] signature = new byte[]{0x00, 0x01, 0x02};
        when(imageRepository.findByIdAndOwnerId("image-id", 7L))
                .thenReturn(Optional.of(image));
        when(imageSignatureValidator.matches(eq("image/jpeg"), any(byte[].class)))
                .thenReturn(false);

        assertThatThrownBy(() -> service.completeUpload(
                7L,
                "image-id",
                new ImageObjectStorage.StoredImageObject(3, "image/jpeg", signature)
        ))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_IMAGE_CONTENT));
        assertThat(image.getStatus()).isEqualTo(ImageStatus.REJECTED);
    }

    @Test
    void rejectsExpiredUploadBeforeStorageInspection() {
        Image image = pendingImage(NOW);
        when(imageRepository.findByIdAndOwnerId("image-id", 7L))
                .thenReturn(Optional.of(image));

        assertThatThrownBy(() -> service.getPendingUpload(7L, "image-id"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.IMAGE_UPLOAD_EXPIRED));
    }

    private Image pendingImage(Instant expiresAt) {
        Member owner = Member.create(
                "owner@fitback.com",
                "owner",
                "password",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(owner, "id", 7L);
        return Image.createPending(
                "image-id",
                owner,
                "prod/images/analysis_original/2026/07/image-id.jpg",
                ImagePurpose.ANALYSIS_ORIGINAL,
                "image/jpeg",
                3,
                ImageVisibility.PRIVATE,
                expiresAt
        );
    }
}

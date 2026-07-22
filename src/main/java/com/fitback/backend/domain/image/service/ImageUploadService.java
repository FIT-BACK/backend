package com.fitback.backend.domain.image.service;

import com.fitback.backend.domain.image.dto.ImageCompleteResponse;
import com.fitback.backend.domain.image.dto.ImageUploadRequest;
import com.fitback.backend.domain.image.dto.ImageUploadResponse;
import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImagePurpose;
import com.fitback.backend.domain.image.entity.ImageStatus;
import com.fitback.backend.domain.image.entity.ImageVisibility;
import com.fitback.backend.domain.image.repository.ImageRepository;
import com.fitback.backend.domain.image.service.port.ImageUploadUrl;
import com.fitback.backend.domain.image.service.port.ImageUploadUrlPort;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ImageUploadService {

    private static final Duration UPLOAD_URL_EXPIRATION = Duration.ofMinutes(5);
    private static final Map<String, String> CONTENT_TYPE_EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp"
    );

    private final ImageRepository imageRepository;
    private final ImageUploadUrlPort imageUploadUrlPort;
    private final ImageObjectStorage imageObjectStorage;
    private final ImageAccessUrlProvider imageAccessUrlProvider;
    private final ImageSignatureValidator imageSignatureValidator;
    private final Clock clock;

    @Transactional
    public ImageUploadResponse createUpload(Member owner, ImageUploadRequest request) {
        String contentType = normalizeContentType(request.contentType());
        Instant now = clock.instant();
        Instant expiresAt = now.plus(UPLOAD_URL_EXPIRATION);
        String imageId = UUID.randomUUID().toString();
        String objectKey = createObjectKey(imageId, request.purpose(), contentType, now);

        Image image = Image.createPending(
                imageId,
                owner,
                objectKey,
                request.purpose(),
                contentType,
                request.fileSize(),
                ImageVisibility.PRIVATE,
                expiresAt
        );
        ImageUploadUrl uploadUrl = createUploadUrl(image);
        imageRepository.save(image);
        return toResponse(image, uploadUrl, expiresAt);
    }

    @Transactional
    public ImageUploadResponse reissueUpload(Member owner, String imageId) {
        Image image = findOwnedImage(owner.getId(), imageId);
        if (image.getStatus() != ImageStatus.PENDING) {
            throw new BusinessException(ErrorCode.IMAGE_INVALID_STATE);
        }
        Instant expiresAt = clock.instant().plus(UPLOAD_URL_EXPIRATION);
        image.renewUpload(expiresAt);
        return toResponse(image, createUploadUrl(image), expiresAt);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public ImageCompleteResponse completeUpload(Member owner, String imageId) {
        Image image = findOwnedImage(owner.getId(), imageId);
        if (image.getStatus() != ImageStatus.PENDING) {
            throw new BusinessException(ErrorCode.IMAGE_INVALID_STATE);
        }
        if (image.uploadExpired(clock.instant())) {
            throw new BusinessException(ErrorCode.IMAGE_UPLOAD_EXPIRED);
        }

        ImageObjectStorage.StoredImageObject storedObject =
                imageObjectStorage.inspect(image.getObjectKey());
        LocalDateTime completedAt = LocalDateTime.now(clock);
        if (!imageSignatureValidator.matches(
                image.getContentType(),
                storedObject.signatureBytes()
        )) {
            image.reject(completedAt);
            throw new BusinessException(ErrorCode.INVALID_IMAGE_CONTENT);
        }
        try {
            image.completeUpload(
                    storedObject.fileSizeBytes(),
                    storedObject.contentType(),
                    completedAt
            );
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_IMAGE_CONTENT);
        }
        return new ImageCompleteResponse(image.getId(), image.getStatus().name());
    }

    @Transactional
    public Image activateAnalysisImage(Long memberId, String imageId) {
        Image image = findOwnedImage(memberId, imageId);
        try {
            image.activateForAnalysis(memberId, clock.instant());
            return image;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.IMAGE_NOT_FOUND);
        } catch (IllegalStateException exception) {
            throw new BusinessException(ErrorCode.IMAGE_INVALID_STATE);
        }
    }

    @Transactional(readOnly = true)
    public String createReadUrl(Image image) {
        return imageAccessUrlProvider.createReadUrl(image);
    }

    private Image findOwnedImage(Long ownerId, String imageId) {
        return imageRepository.findByIdAndOwnerId(imageId, ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.IMAGE_NOT_FOUND));
    }

    private String normalizeContentType(String requestedContentType) {
        String contentType = requestedContentType.toLowerCase(Locale.ROOT);
        if (!CONTENT_TYPE_EXTENSIONS.containsKey(contentType)) {
            throw new BusinessException(ErrorCode.IMAGE_UNSUPPORTED_CONTENT_TYPE);
        }
        return contentType;
    }

    private ImageUploadUrl createUploadUrl(Image image) {
        return imageUploadUrlPort.create(
                image.getObjectKey(),
                image.getContentType(),
                image.getFileSize(),
                UPLOAD_URL_EXPIRATION
        );
    }

    private String createObjectKey(
            String imageId,
            ImagePurpose purpose,
            String contentType,
            Instant createdAt
    ) {
        ZonedDateTime date = createdAt.atZone(ZoneOffset.UTC);
        return "prod/images/%s/%04d/%02d/%s.%s".formatted(
                purpose.name().toLowerCase(Locale.ROOT),
                date.getYear(),
                date.getMonthValue(),
                imageId,
                CONTENT_TYPE_EXTENSIONS.get(contentType)
        );
    }

    private ImageUploadResponse toResponse(
            Image image,
            ImageUploadUrl uploadUrl,
            Instant expiresAt
    ) {
        return new ImageUploadResponse(
                image.getId(),
                uploadUrl.uploadUrl(),
                uploadUrl.uploadMethod(),
                uploadUrl.requiredHeaders(),
                expiresAt,
                uploadUrl.imageUrl()
        );
    }
}

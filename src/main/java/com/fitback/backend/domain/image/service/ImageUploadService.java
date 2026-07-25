package com.fitback.backend.domain.image.service;

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
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ImageUploadService {

    private static final Logger log = LoggerFactory.getLogger(ImageUploadService.class);
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
    private final ImageUploadTransactionService imageUploadTransactionService;
    private final Clock clock;

    @Transactional
    public ImageUploadResponse createUpload(Member owner, ImageUploadRequest request) {
        String contentType = normalizeContentType(request.contentType());
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(UPLOAD_URL_EXPIRATION);
        String imageId = UUID.randomUUID().toString();
        String objectKey = createObjectKey(
                imageId,
                owner.getId(),
                request.purpose(),
                contentType,
                now
        );

        Image image = Image.createPending(
                imageId,
                owner,
                objectKey,
                toStoredPurpose(request.purpose()),
                contentType,
                request.fileSize(),
                ImageVisibility.PRIVATE,
                expiresAt
        );
        ImageUploadUrl uploadUrl = createUploadUrl(image, expiresAt);
        imageRepository.save(image);
        return toResponse(image, uploadUrl, expiresAt);
    }

    @Transactional
    public ImageUploadResponse reissueUpload(Member owner, String imageId) {
        Image image = findOwnedImage(owner.getId(), imageId);
        if (!image.getStatus().isPendingUpload()) {
            throw new BusinessException(ErrorCode.IMAGE_INVALID_STATE);
        }
        Instant expiresAt = clock.instant()
                .truncatedTo(ChronoUnit.SECONDS)
                .plus(UPLOAD_URL_EXPIRATION);
        image.renewUpload(expiresAt);
        return toResponse(image, createUploadUrl(image, expiresAt), expiresAt);
    }

    public ImageCompleteResponse completeUpload(Member owner, String imageId) {
        ImageUploadTransactionService.PendingUpload pendingUpload =
                imageUploadTransactionService.getPendingUpload(owner.getId(), imageId);
        // S3 조회는 DB 잠금을 놓은 뒤 수행하고, 결과 반영 시 엔티티를 다시 검증한다.
        ImageObjectStorage.StoredImageObject storedObject =
                imageObjectStorage.inspect(pendingUpload.objectKey());
        return imageUploadTransactionService.completeUpload(
                owner.getId(),
                imageId,
                storedObject
        );
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

    private ImageUploadUrl createUploadUrl(Image image, Instant expiresAt) {
        try {
            return imageUploadUrlPort.create(
                    image.getObjectKey(),
                    image.getContentType(),
                    image.getFileSize(),
                    expiresAt
            );
        } catch (RuntimeException exception) {
            log.error(
                    "Image upload information generation failed. imageId={}",
                    image.getId(),
                    exception
            );
            throw new BusinessException(ErrorCode.IMAGE_PRESIGN_ERROR);
        }
    }

    private String createObjectKey(
            String imageId,
            Long ownerId,
            ImageUploadPurpose purpose,
            String contentType,
            Instant createdAt
    ) {
        ZonedDateTime date = createdAt.atZone(ZoneOffset.UTC);
        return "images/%s/%d/%04d/%02d/%s.%s".formatted(
                purpose.name().toLowerCase(Locale.ROOT),
                Objects.requireNonNull(ownerId, "owner id must not be null"),
                date.getYear(),
                date.getMonthValue(),
                imageId,
                CONTENT_TYPE_EXTENSIONS.get(contentType)
        );
    }

    private ImagePurpose toStoredPurpose(ImageUploadPurpose purpose) {
        return switch (purpose) {
            case ANALYSIS -> ImagePurpose.ANALYSIS_ORIGINAL;
            case LOOKBOOK -> ImagePurpose.LOOKBOOK_ORIGINAL;
            case PROFILE -> ImagePurpose.PROFILE;
        };
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
                uploadUrl.uploadFields(),
                expiresAt
        );
    }
}

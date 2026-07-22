package com.fitback.backend.domain.image.service;

import com.fitback.backend.domain.image.dto.ImageCompleteResponse;
import com.fitback.backend.domain.image.dto.ImageUploadRequest;
import com.fitback.backend.domain.image.dto.ImageUploadResponse;
import com.fitback.backend.domain.image.entity.ImageAsset;
import com.fitback.backend.domain.image.entity.ImageAssetStatus;
import com.fitback.backend.domain.image.entity.ImagePurpose;
import com.fitback.backend.domain.image.repository.ImageAssetRepository;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ImageAssetService {

    private static final Duration UPLOAD_EXPIRY = Duration.ofMinutes(5);
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp"
    );

    private final ImageAssetRepository imageAssetRepository;
    private final MemberRepository memberRepository;
    private final ImageObjectStorage imageObjectStorage;
    private final ImageAccessUrlProvider imageAccessUrlProvider;
    private final ImageSignatureValidator imageSignatureValidator;
    private final Clock clock;

    @Transactional
    public ImageUploadResponse issueUploadRequest(Long memberId, ImageUploadRequest request) {
        validateUploadRequest(request);
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Instant expiresAt = clock.instant().plus(UPLOAD_EXPIRY);
        String storageKey = createStorageKey(
                request.purpose(),
                memberId,
                request.contentType()
        );
        ImageAsset imageAsset = ImageAsset.create(
                member,
                request.purpose(),
                storageKey,
                request.contentType(),
                request.fileSize(),
                expiresAt
        );
        imageAssetRepository.save(imageAsset);

        ImageObjectStorage.PresignedPost presignedPost = imageObjectStorage.createPresignedPost(
                storageKey,
                request.contentType(),
                request.fileSize(),
                expiresAt
        );
        return toUploadResponse(imageAsset, presignedPost);
    }

    @Transactional
    public ImageUploadResponse reissueUploadRequest(Long memberId, String imageId) {
        ImageAsset imageAsset = findOwnedImage(memberId, imageId);
        if (imageAsset.getAssetStatus() != ImageAssetStatus.PENDING_UPLOAD) {
            throw new BusinessException(ErrorCode.IMAGE_INVALID_STATE);
        }
        Instant expiresAt = clock.instant().plus(UPLOAD_EXPIRY);
        imageAsset.renewUpload(expiresAt);
        ImageObjectStorage.PresignedPost presignedPost = imageObjectStorage.createPresignedPost(
                imageAsset.getStorageKey(),
                imageAsset.getMimeType(),
                imageAsset.getFileSizeBytes(),
                expiresAt
        );
        return toUploadResponse(imageAsset, presignedPost);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public ImageCompleteResponse completeUpload(Long memberId, String imageId) {
        ImageAsset imageAsset = findOwnedImage(memberId, imageId);
        if (imageAsset.getAssetStatus() != ImageAssetStatus.PENDING_UPLOAD) {
            throw new BusinessException(ErrorCode.IMAGE_INVALID_STATE);
        }
        if (imageAsset.uploadExpired(clock.instant())) {
            throw new BusinessException(ErrorCode.IMAGE_UPLOAD_EXPIRED);
        }

        ImageObjectStorage.StoredImageObject storedObject =
                imageObjectStorage.inspect(imageAsset.getStorageKey());
        if (!imageSignatureValidator.matches(
                imageAsset.getMimeType(),
                storedObject.signatureBytes()
        )) {
            imageAsset.reject(LocalDateTime.now(clock));
            throw new BusinessException(ErrorCode.INVALID_IMAGE_CONTENT);
        }
        try {
            imageAsset.completeUpload(
                    storedObject.fileSizeBytes(),
                    storedObject.mimeType(),
                    LocalDateTime.now(clock)
            );
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_IMAGE_CONTENT);
        }
        return new ImageCompleteResponse(imageAsset.getPublicId(), imageAsset.getAssetStatus().name());
    }

    @Transactional
    public ImageAsset activateAnalysisImage(Long memberId, String imageId) {
        ImageAsset imageAsset = findOwnedImage(memberId, imageId);
        try {
            // 소유권, 업로드 목적, READY 상태를 검증한 뒤 같은 트랜잭션에서 리포트에 연결한다.
            imageAsset.activateForAnalysis(memberId, clock.instant());
            return imageAsset;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.IMAGE_NOT_FOUND);
        } catch (IllegalStateException exception) {
            throw new BusinessException(ErrorCode.IMAGE_INVALID_STATE);
        }
    }

    @Transactional(readOnly = true)
    public String createReadUrl(ImageAsset imageAsset) {
        return imageAccessUrlProvider.createReadUrl(imageAsset);
    }

    private ImageAsset findOwnedImage(Long memberId, String imageId) {
        return imageAssetRepository.findByPublicIdAndOwnerId(imageId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.IMAGE_NOT_FOUND));
    }

    private void validateUploadRequest(ImageUploadRequest request) {
        if (request.purpose() == null) {
            throw new BusinessException(ErrorCode.INVALID_IMAGE_PURPOSE);
        }
        if (!ALLOWED_MIME_TYPES.contains(request.contentType())) {
            throw new BusinessException(ErrorCode.INVALID_IMAGE_TYPE);
        }
        if (request.fileSize() <= 0 || request.fileSize() > ImageAsset.MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.IMAGE_TOO_LARGE);
        }
    }

    private String createStorageKey(
            ImagePurpose purpose,
            Long memberId,
            String contentType
    ) {
        String datePath = DateTimeFormatter.ofPattern("yyyy/MM")
                .withZone(clock.getZone())
                .format(clock.instant());
        return "images/%s/%d/%s/%s.%s".formatted(
                purpose.name().toLowerCase(Locale.ROOT),
                memberId,
                datePath,
                UUID.randomUUID(),
                EXTENSIONS.get(contentType)
        );
    }

    private ImageUploadResponse toUploadResponse(
            ImageAsset imageAsset,
            ImageObjectStorage.PresignedPost presignedPost
    ) {
        return new ImageUploadResponse(
                imageAsset.getPublicId(),
                presignedPost.uploadUrl(),
                "POST",
                presignedPost.uploadFields(),
                presignedPost.expiresAt()
        );
    }
}

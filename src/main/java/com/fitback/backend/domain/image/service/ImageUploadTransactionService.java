package com.fitback.backend.domain.image.service;

import com.fitback.backend.domain.image.dto.ImageCompleteResponse;
import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImageStatus;
import com.fitback.backend.domain.image.repository.ImageRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ImageUploadTransactionService {

    private final ImageRepository imageRepository;
    private final ImageSignatureValidator imageSignatureValidator;
    private final Clock clock;

    @Transactional
    public PendingUpload getPendingUpload(Long ownerId, String imageId) {
        Image image = findOwnedImage(ownerId, imageId);
        validatePendingUpload(image);
        return new PendingUpload(image.getObjectKey());
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public ImageCompleteResponse completeUpload(
            Long ownerId,
            String imageId,
            ImageObjectStorage.StoredImageObject storedObject
    ) {
        Image image = findOwnedImage(ownerId, imageId);
        if (image.getStatus() != ImageStatus.PENDING) {
            throw new BusinessException(ErrorCode.IMAGE_INVALID_STATE);
        }

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

    private Image findOwnedImage(Long ownerId, String imageId) {
        return imageRepository.findByIdAndOwnerId(imageId, ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.IMAGE_NOT_FOUND));
    }

    private void validatePendingUpload(Image image) {
        if (image.getStatus() != ImageStatus.PENDING) {
            throw new BusinessException(ErrorCode.IMAGE_INVALID_STATE);
        }
        if (image.uploadExpired(clock.instant())) {
            throw new BusinessException(ErrorCode.IMAGE_UPLOAD_EXPIRED);
        }
    }

    public record PendingUpload(String objectKey) {
    }
}

package com.fitback.backend.domain.image.service;

import com.fitback.backend.domain.image.dto.ImageUploadRequest;
import com.fitback.backend.domain.image.dto.ImageUploadResponse;
import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImagePurpose;
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
    private final Clock clock;

    @Transactional
    public ImageUploadResponse createUpload(Member owner, ImageUploadRequest request) {
        String contentType = request.contentType().toLowerCase(Locale.ROOT);
        String extension = CONTENT_TYPE_EXTENSIONS.get(contentType);
        if (extension == null) {
            throw new BusinessException(ErrorCode.IMAGE_UNSUPPORTED_CONTENT_TYPE);
        }

        Instant now = clock.instant();
        Instant expiresAt = now.plus(UPLOAD_URL_EXPIRATION);
        String imageId = UUID.randomUUID().toString();
        String objectKey = createObjectKey(imageId, request.purpose(), extension, now);

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

        ImageUploadUrl uploadUrl = imageUploadUrlPort.create(
                objectKey,
                contentType,
                UPLOAD_URL_EXPIRATION
        );
        imageRepository.save(image);

        return new ImageUploadResponse(
                imageId,
                uploadUrl.uploadUrl(),
                uploadUrl.uploadMethod(),
                uploadUrl.requiredHeaders(),
                expiresAt,
                uploadUrl.imageUrl()
        );
    }

    private String createObjectKey(
            String imageId,
            ImagePurpose purpose,
            String extension,
            Instant createdAt
    ) {
        ZonedDateTime date = createdAt.atZone(ZoneOffset.UTC);
        return "prod/images/%s/%04d/%02d/%s.%s".formatted(
                purpose.name().toLowerCase(Locale.ROOT),
                date.getYear(),
                date.getMonthValue(),
                imageId,
                extension
        );
    }
}

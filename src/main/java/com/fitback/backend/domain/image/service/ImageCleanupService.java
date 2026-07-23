package com.fitback.backend.domain.image.service;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImageStatus;
import com.fitback.backend.domain.image.repository.ImageRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ImageCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ImageCleanupService.class);
    private static final int CLEANUP_BATCH_SIZE = 50;
    private static final Duration RETRY_DELAY = Duration.ofHours(1);

    private final ImageRepository imageRepository;
    private final ImageObjectStorage imageObjectStorage;
    private final List<ImageReferenceProbe> imageReferenceProbes;
    private final Clock clock;

    @Transactional
    public List<String> claimExpiredImages() {
        LocalDateTime createdBefore = LocalDateTime.now(clock).minusHours(24);
        List<ImageStatus> cleanupStatuses = List.of(
                ImageStatus.PENDING,
                ImageStatus.PENDING_UPLOAD,
                ImageStatus.READY,
                ImageStatus.REJECTED,
                ImageStatus.DELETE_FAILED
        );
        // 도메인 참조를 확인한 뒤 잠금 트랜잭션 안에서 DELETING 상태로 선점한다.
        List<String> claimedImageIds = new ArrayList<>();
        String afterId = null;
        while (claimedImageIds.size() < CLEANUP_BATCH_SIZE) {
            List<Image> candidates = imageRepository.findCleanupCandidates(
                    cleanupStatuses,
                    createdBefore,
                    clock.instant(),
                    afterId,
                    PageRequest.of(0, CLEANUP_BATCH_SIZE)
            );
            if (candidates.isEmpty()) {
                break;
            }

            for (Image image : candidates) {
                boolean referenced = imageReferenceProbes.stream()
                        .anyMatch(probe -> probe.exists(image.getId()));
                if (!referenced) {
                    image.claimForDeletion(clock.instant());
                    claimedImageIds.add(image.getId());
                    if (claimedImageIds.size() == CLEANUP_BATCH_SIZE) {
                        break;
                    }
                }
            }

            afterId = candidates.get(candidates.size() - 1).getId();
            if (candidates.size() < CLEANUP_BATCH_SIZE) {
                break;
            }
        }
        return claimedImageIds;
    }

    @Transactional
    public void deleteClaimedImage(String imageId) {
        Image image = imageRepository.findById(imageId).orElse(null);
        if (image == null || image.getStatus() != ImageStatus.DELETING) {
            return;
        }
        try {
            imageObjectStorage.delete(image.getObjectKey());
            image.markDeleted(clock.instant());
        } catch (RuntimeException exception) {
            image.markDeleteFailed(clock.instant().plus(RETRY_DELAY));
            log.warn("Temporary image cleanup failed. imageId={}", imageId, exception);
        }
    }
}

package com.fitback.backend.domain.image.service;

import com.fitback.backend.domain.image.entity.ImageAsset;
import com.fitback.backend.domain.image.entity.ImageAssetStatus;
import com.fitback.backend.domain.image.repository.ImageAssetRepository;
import java.time.Clock;
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

    private final ImageAssetRepository imageAssetRepository;
    private final ImageObjectStorage imageObjectStorage;
    private final List<ImageReferenceProbe> imageReferenceProbes;
    private final Clock clock;

    @Transactional
    public List<Long> claimExpiredImages() {
        LocalDateTime createdBefore = LocalDateTime.now(clock).minusHours(24);
        List<ImageAsset> candidates = imageAssetRepository.findCleanupCandidates(
                List.of(
                        ImageAssetStatus.PENDING_UPLOAD,
                        ImageAssetStatus.READY,
                        ImageAssetStatus.REJECTED,
                        ImageAssetStatus.DELETE_FAILED
                ),
                createdBefore,
                PageRequest.of(0, CLEANUP_BATCH_SIZE)
        );
        // 실제 도메인 참조를 다시 확인한 뒤 DB 잠금 안에서 DELETING으로 선점한다.
        List<Long> claimedImageIds = new ArrayList<>();
        for (ImageAsset image : candidates) {
            boolean isReferenced = imageReferenceProbes.stream()
                    .anyMatch(probe -> probe.exists(image.getId()));
            if (!isReferenced) {
                image.claimForDeletion();
                claimedImageIds.add(image.getId());
            }
        }
        return claimedImageIds;
    }

    @Transactional
    public void deleteClaimedImage(Long imageId) {
        ImageAsset imageAsset = imageAssetRepository.findById(imageId).orElse(null);
        if (imageAsset == null || imageAsset.getAssetStatus() != ImageAssetStatus.DELETING) {
            return;
        }
        try {
            imageObjectStorage.delete(imageAsset.getStorageKey());
            imageAsset.markDeleted(clock.instant());
        } catch (RuntimeException exception) {
            imageAsset.markDeleteFailed();
            log.warn("Temporary image cleanup failed. imageId={}", imageId, exception);
        }
    }
}

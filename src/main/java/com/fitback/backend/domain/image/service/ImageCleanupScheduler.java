package com.fitback.backend.domain.image.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ImageCleanupScheduler {

    private final ImageCleanupService imageCleanupService;

    @Scheduled(
            fixedDelayString = "${image.cleanup.fixed-delay-ms:3600000}",
            initialDelayString = "${image.cleanup.initial-delay-ms:3600000}"
    )
    public void cleanupTemporaryImages() {
        imageCleanupService.claimExpiredImages()
                .forEach(imageCleanupService::deleteClaimedImage);
    }
}

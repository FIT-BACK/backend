package com.fitback.backend.domain.image.service;

import com.fitback.backend.domain.image.event.ImageReferencesReleasedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class ImageReferenceReleaseListener {

    private final ImageCleanupService imageCleanupService;

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true
    )
    public void release(ImageReferencesReleasedEvent event) {
        imageCleanupService.claimReleasedActiveImages(event.imageIds())
                .forEach(imageCleanupService::deleteClaimedImage);
    }
}

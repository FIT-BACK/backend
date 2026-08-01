package com.fitback.backend.domain.image.service;

import com.fitback.backend.domain.image.event.ImageReferencesReleasedEvent;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class ImageReferenceReleaseListener {

    private static final Logger log = LoggerFactory.getLogger(ImageReferenceReleaseListener.class);

    private final ImageCleanupService imageCleanupService;

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true
    )
    public void release(ImageReferencesReleasedEvent event) {
        try {
            imageCleanupService.claimReleasedActiveImages(event.imageIds())
                    .forEach(imageCleanupService::deleteClaimedImage);
        } catch (RuntimeException exception) {
            String errorCode = exception instanceof BusinessException businessException
                    ? businessException.getErrorCode().getCode()
                    : ErrorCode.INTERNAL_SERVER_ERROR.getCode();
            log.error(
                    "Released image cleanup failed. imageIds={}, errorCode={}, failureType={}",
                    event.imageIds(),
                    errorCode,
                    exception.getClass().getSimpleName(),
                    stackTraceOnly(exception)
            );
        }
    }

    private RuntimeException stackTraceOnly(RuntimeException exception) {
        RuntimeException sanitized = new RuntimeException(exception.getClass().getSimpleName());
        sanitized.setStackTrace(exception.getStackTrace());
        return sanitized;
    }
}

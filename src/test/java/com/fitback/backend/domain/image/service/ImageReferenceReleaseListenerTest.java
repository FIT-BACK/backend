package com.fitback.backend.domain.image.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.image.event.ImageReferencesReleasedEvent;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImageReferenceReleaseListenerTest {

    @Test
    void isolatesCleanupFailureAfterTransactionCommit() {
        ImageCleanupService imageCleanupService = mock(ImageCleanupService.class);
        when(imageCleanupService.claimReleasedActiveImages(List.of("image-1")))
                .thenThrow(new BusinessException(ErrorCode.IMAGE_STORAGE_UNAVAILABLE));
        ImageReferenceReleaseListener listener =
                new ImageReferenceReleaseListener(imageCleanupService);

        assertThatCode(() -> listener.release(
                new ImageReferencesReleasedEvent(List.of("image-1"))
        )).doesNotThrowAnyException();
    }
}

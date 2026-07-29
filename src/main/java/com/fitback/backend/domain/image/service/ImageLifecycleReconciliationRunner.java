package com.fitback.backend.domain.image.service;

import com.fitback.backend.domain.image.repository.ImageRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ImageLifecycleReconciliationRunner implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(ImageLifecycleReconciliationRunner.class);

    private final ImageRepository imageRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int reconciledRows = imageRepository.reconcileLegacyLifecycleValues();
        long remainingLegacyRows = imageRepository.countLegacyLifecycleValues();
        if (remainingLegacyRows != 0) {
            throw new IllegalStateException(
                    "legacy image lifecycle values remain after reconciliation"
            );
        }
        log.info(
                "Image lifecycle reconciliation completed. reconciledRows={},"
                        + " remainingLegacyRows={}",
                reconciledRows,
                remainingLegacyRows
        );
    }
}

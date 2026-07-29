package com.fitback.backend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class UnavailableMultipartImageStorageTest {

    @Test
    void rejectsProductionMultipartStorageWithUploadFlowError() {
        UnavailableMultipartImageStorage imageStorage = new UnavailableMultipartImageStorage();

        assertThatThrownBy(() -> imageStorage.store(null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_IMAGE_UPLOAD_FLOW_REQUIRED);
    }
}

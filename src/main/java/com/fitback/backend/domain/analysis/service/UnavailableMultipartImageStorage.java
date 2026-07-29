package com.fitback.backend.domain.analysis.service;

import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
@Profile("prod")
public class UnavailableMultipartImageStorage implements ImageStorage {

    @Override
    public String store(MultipartFile image) {
        throw new BusinessException(ErrorCode.ANALYSIS_IMAGE_UPLOAD_FLOW_REQUIRED);
    }

    @Override
    public void delete(String imageUrl) {
        // 운영 multipart 저장은 생성되지 않으므로 정리할 로컬 파일이 없다.
    }
}

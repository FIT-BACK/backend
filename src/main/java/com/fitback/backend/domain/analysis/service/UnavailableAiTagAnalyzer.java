package com.fitback.backend.domain.analysis.service;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
@Profile("prod")
@ConditionalOnProperty(
        name = "fitback.ai.tag-analyzer",
        havingValue = "unavailable",
        matchIfMissing = true
)
public class UnavailableAiTagAnalyzer implements AiTagAnalyzer {

    @Override
    public AiTagAnalysisResult analyze(MultipartFile image) {
        throw unavailableAnalyzer();
    }

    @Override
    public AiTagAnalysisResult analyze(Image image) {
        throw unavailableAnalyzer();
    }

    private BusinessException unavailableAnalyzer() {
        // 실제 AI 연동 전에는 운영 데이터에 데모 태그를 저장하지 않고 실패로 종료한다.
        return new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
    }
}

package com.fitback.backend.domain.contentsearch.service;

import com.fitback.backend.domain.contentsearch.dto.ContentSearchResponse;
import com.fitback.backend.domain.lookbook.service.LookbookService;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.trend.service.TrendService;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class ContentSearchService {

    private static final int MAX_KEYWORD_LENGTH = 100;

    private final TrendService trendService;
    private final LookbookService lookbookService;

    public ContentSearchService(
            TrendService trendService,
            LookbookService lookbookService
    ) {
        this.trendService = trendService;
        this.lookbookService = lookbookService;
    }

    public ContentSearchResponse search(String keyword, Member member) {
        String normalizedKeyword = normalize(keyword);
        return new ContentSearchResponse(
                trendService.searchTrends(normalizedKeyword, member),
                lookbookService.searchLookbooks(normalizedKeyword, member)
        );
    }

    private static String normalize(String keyword) {
        if (keyword == null) {
            throw validationError();
        }
        String normalized = keyword.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_KEYWORD_LENGTH) {
            throw validationError();
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static BusinessException validationError() {
        return new BusinessException(
                ErrorCode.VALIDATION_ERROR,
                "keyword는 공백 제거 후 1자 이상 100자 이하여야 합니다."
        );
    }
}

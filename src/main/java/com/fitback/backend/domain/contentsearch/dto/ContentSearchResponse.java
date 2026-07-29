package com.fitback.backend.domain.contentsearch.dto;

import com.fitback.backend.domain.lookbook.dto.LookbookResponse;
import com.fitback.backend.domain.trend.dto.TrendResponse;
import java.util.List;

public record ContentSearchResponse(
        List<TrendResponse.TrendItem> trends,
        List<LookbookResponse.LookbookItem> lookbooks
) {

    public ContentSearchResponse {
        trends = List.copyOf(trends);
        lookbooks = List.copyOf(lookbooks);
    }
}

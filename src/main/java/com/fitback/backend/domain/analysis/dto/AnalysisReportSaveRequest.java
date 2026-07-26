package com.fitback.backend.domain.analysis.dto;

import com.fitback.backend.domain.product.service.model.ProductCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AnalysisReportSaveRequest(
        @NotNull(message = "선택 상품 목록은 필수입니다.")
        @Size(min = 1, max = 8, message = "선택 상품은 1개 이상 8개 이하여야 합니다.")
        List<@Valid SelectedItem> selectedItems
) {

    public AnalysisReportSaveRequest {
        selectedItems = selectedItems == null ? null : List.copyOf(selectedItems);
    }

    public record SelectedItem(
            @NotNull(message = "상품 카테고리는 필수입니다.")
            ProductCategory category,

            @NotNull(message = "상품 ID는 필수입니다.")
            @Positive(message = "상품 ID는 양수여야 합니다.")
            Long productId
    ) {
    }
}

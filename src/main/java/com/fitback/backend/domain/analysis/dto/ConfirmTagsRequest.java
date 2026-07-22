package com.fitback.backend.domain.analysis.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record ConfirmTagsRequest(
        @NotEmpty(message = "확정할 태그를 한 개 이상 선택해주세요.")
        List<@NotNull @Positive Long> confirmedTagIds,

        @NotNull(message = "매칭 정도는 필수입니다.")
        @Min(value = 0, message = "매칭 정도는 0 이상이어야 합니다.")
        @Max(value = 100, message = "매칭 정도는 100 이하여야 합니다.")
        Integer matchPercentage
) {
}

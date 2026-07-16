package com.fitback.backend.domain.lookbook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

// 룩북 요청 DTO
public final class LookbookRequest {

    private LookbookRequest() {
    }

    // 룩북 업로드
    public record LookbookCreate(
            @NotBlank(message = "원본 룩 이미지 URL은 필수입니다.")
            @Size(max = 2048, message = "원본 룩 이미지 URL은 2048자 이하여야 합니다.")
            String originalImageUrl,

            @NotBlank(message = "가성비 매칭 이미지 URL은 필수입니다.")
            @Size(max = 2048, message = "가성비 매칭 이미지 URL은 2048자 이하여야 합니다.")
            String matchedImageUrl,

            @NotEmpty(message = "스타일 태그는 하나 이상 선택해야 합니다.")
            List<
                    @NotNull(message = "태그 ID는 null일 수 없습니다.")
                    @Positive(message = "태그 ID는 양수여야 합니다.")
                    Long
            > tagIds,

            @Size(max = 2048, message = "구매 URL은 2048자 이하여야 합니다.")
            String purchaseUrl,

            @Size(max = 500, message = "한 줄 코멘트는 500자 이하여야 합니다.")
            String comment
    ) {
    }
}

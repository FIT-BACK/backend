package com.fitback.backend.domain.lookbook.dto;

import com.fitback.backend.domain.lookbook.entity.LookbookReportReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.hibernate.validator.constraints.URL;

// 룩북 요청 DTO
public final class LookbookRequest {

    private LookbookRequest() {
    }

    // 룩북 업로드
    @Schema(name = "LookbookCreateRequest")
    public record LookbookCreate(
            @NotBlank(message = "원본 룩 이미지 ID는 필수입니다.")
            String originalImageId,

            @NotBlank(message = "가성비 매칭 이미지 ID는 필수입니다.")
            String matchedImageId,

            @Size(max = 2048, message = "구매 URL은 2048자 이하여야 합니다.")
            @URL(
                    regexp = "^https?://.*$",
                    message = "올바른 링크 형식을 입력해주세요."
            )
            String purchaseUrl,

            @NotNull(message = "스타일 태그는 필수입니다.")
            @Size(min = 1, max = 5, message = "스타일 태그는 1개 이상 5개 이하여야 합니다.")
            List<
                    @NotNull(message = "태그 ID는 null일 수 없습니다.")
                    @Positive(message = "태그 ID는 양수여야 합니다.")
                    Long
            > tagIds,

            @Size(max = 500, message = "한 줄 코멘트는 500자 이하여야 합니다.")
            String comment
    ) {

        public LookbookCreate {
            if (purchaseUrl != null) {
                purchaseUrl = purchaseUrl.trim();
                if (purchaseUrl.isEmpty()) {
                    purchaseUrl = null;
                }
            }
        }
    }

    // 룩북 수정
    @Schema(name = "LookbookUpdateRequest")
    public record LookbookUpdate(
            @NotBlank(message = "원본 룩 이미지 ID는 필수입니다.")
            String originalImageId,

            @NotBlank(message = "가성비 매칭 이미지 ID는 필수입니다.")
            String matchedImageId,

            @Size(max = 2048, message = "구매 URL은 2048자 이하여야 합니다.")
            @URL(
                    regexp = "^https?://.*$",
                    message = "올바른 링크 형식을 입력해주세요."
            )
            String purchaseUrl,

            @NotNull(message = "스타일 태그는 필수입니다.")
            @Size(min = 1, max = 5, message = "스타일 태그는 1개 이상 5개 이하여야 합니다.")
            List<
                    @NotNull(message = "태그 ID는 null일 수 없습니다.")
                    @Positive(message = "태그 ID는 양수여야 합니다.")
                    Long
            > tagIds,

            @Size(max = 500, message = "한 줄 코멘트는 500자 이하여야 합니다.")
            String comment
    ) {

        public LookbookUpdate {
            if (purchaseUrl != null) {
                purchaseUrl = purchaseUrl.trim();
                if (purchaseUrl.isEmpty()) {
                    purchaseUrl = null;
                }
            }
        }
    }

    // 룩북 신고
    @Schema(name = "LookbookReportRequest")
    public record LookbookReport(
            @NotNull(message = "신고 사유는 필수입니다.")
            LookbookReportReason reason
    ) {
    }
}

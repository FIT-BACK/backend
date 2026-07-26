package com.fitback.backend.domain.closet.dto;

import com.fitback.backend.domain.closet.entity.ClosetTargetType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

// 마이 클로젯 요청 DTO
public final class ClosetSaveRequest {

    private ClosetSaveRequest() {
    }

    // 마이 클로젯 저장
    public record Create(
            @NotNull(message = "저장 대상 타입은 필수입니다.")
            ClosetTargetType targetType,

            @NotNull(message = "저장 대상 ID는 필수입니다.")
            @Positive(message = "저장 대상 ID는 양수여야 합니다.")
            Long targetId
    ) {
    }
}

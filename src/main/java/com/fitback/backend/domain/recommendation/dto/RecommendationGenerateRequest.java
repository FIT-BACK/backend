package com.fitback.backend.domain.recommendation.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record RecommendationGenerateRequest(
        @NotNull
        @Size(max = 8)
        List<@NotNull @Positive Long> confirmedTagIds,

        @NotNull
        @Size(max = 8)
        List<@NotBlank @Size(max = 50) String> customTagNames,

        @NotNull
        @Min(0)
        @Max(100)
        Integer matchPercentage
) {

    public RecommendationGenerateRequest {
        if (confirmedTagIds != null) {
            confirmedTagIds = confirmedTagIds.stream().distinct().toList();
        }
        if (customTagNames != null && customTagNames.stream().noneMatch(name -> name == null)) {
            Map<String, String> uniqueNames = new LinkedHashMap<>();
            for (String name : customTagNames) {
                String displayName = Normalizer.normalize(
                        name.trim(),
                        Normalizer.Form.NFKC
                );
                uniqueNames.putIfAbsent(
                        displayName.toLowerCase(Locale.ROOT),
                        displayName
                );
            }
            customTagNames = List.copyOf(uniqueNames.values());
        }
    }

    @AssertTrue(message = "확정 태그는 합계 1개 이상 8개 이하여야 합니다.")
    public boolean hasValidCombinedTagCount() {
        if (confirmedTagIds == null || customTagNames == null) {
            return true;
        }
        int total = confirmedTagIds.size() + customTagNames.size();
        return total >= 1 && total <= 8;
    }
}

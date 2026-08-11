package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.entity.ReportCustomTag;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot.TagInput;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.external.aitag.GarmentPiece;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class RecommendationInputSnapshotFactory {

    public RecommendationInputSnapshot from(AnalysisReport report, Long memberId) {
        List<TagInput> tags = new ArrayList<>();
        report.getDisplayTags().stream()
                .sorted(Comparator.comparing(Tag::getId).thenComparing(Tag::getTagName))
                .map(tag -> new TagInput(
                        tag.getId(),
                        tag.getTagName(),
                        tag.getTagType()
                ))
                .forEach(tags::add);
        List<String> customTagNames = report.getCustomTags().stream()
                .sorted(Comparator.comparing(ReportCustomTag::getNormalizedName))
                .map(ReportCustomTag::getDisplayName)
                .toList();
        return new RecommendationInputSnapshot(
                report.getId(),
                memberId,
                report.getRecommendationInputRevision(),
                report.getMatchPercentage(),
                toProductCategory(report.getGarmentPiece()),
                tags,
                customTagNames
        );
    }

    public boolean matches(
            AnalysisReport report,
            RecommendationInputSnapshot expected
    ) {
        RecommendationInputSnapshot current = from(report, expected.memberId());
        return Objects.equals(current.inputRevision(), expected.inputRevision())
                && Objects.equals(current.matchPercentage(), expected.matchPercentage())
                && Objects.equals(current.category(), expected.category())
                && current.tags().equals(expected.tags())
                && current.customTagNames().equals(expected.customTagNames());
    }

    static ProductCategory toProductCategory(GarmentPiece garmentPiece) {
        if (garmentPiece == null) {
            return null;
        }
        return switch (garmentPiece) {
            case TOP -> ProductCategory.TOP;
            case BOTTOM -> ProductCategory.BOTTOM;
            case DRESS -> ProductCategory.DRESS;
            case OUTER -> ProductCategory.OUTER;
        };
    }
}

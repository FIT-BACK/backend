package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.entity.ReportCustomTag;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot.TagInput;
import com.fitback.backend.domain.tag.entity.Tag;
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
                .map(tag -> new TagInput("TAG:" + tag.getId(), tag.getTagName()))
                .forEach(tags::add);
        report.getCustomTags().stream()
                .sorted(Comparator.comparing(ReportCustomTag::getNormalizedName))
                .map(tag -> new TagInput(
                        "CUSTOM:" + tag.getNormalizedName(),
                        tag.getDisplayName()
                ))
                .forEach(tags::add);
        return new RecommendationInputSnapshot(
                report.getId(),
                memberId,
                report.getRecommendationInputRevision(),
                report.getMatchPercentage(),
                tags
        );
    }

    public boolean matches(
            AnalysisReport report,
            RecommendationInputSnapshot expected
    ) {
        RecommendationInputSnapshot current = from(report, expected.memberId());
        return Objects.equals(current.inputRevision(), expected.inputRevision())
                && Objects.equals(current.matchPercentage(), expected.matchPercentage())
                && current.tagKeys().equals(expected.tagKeys());
    }
}

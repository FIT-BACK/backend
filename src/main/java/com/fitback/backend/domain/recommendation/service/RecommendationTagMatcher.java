package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot.TagInput;
import com.fitback.backend.domain.tag.entity.TagType;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class RecommendationTagMatcher {

    private static final Set<TagType> ELIGIBLE_TAG_TYPES = EnumSet.of(
            TagType.SILHOUETTE,
            TagType.MATERIAL,
            TagType.DETAIL,
            TagType.COLOR
    );

    private RecommendationTagMatcher() {
    }

    static Match match(List<TagInput> tags, ExternalProductCandidate candidate) {
        String searchableText = searchableText(candidate);
        List<TagInput> eligibleTags = tags.stream()
                .filter(tag -> ELIGIBLE_TAG_TYPES.contains(tag.tagType()))
                .toList();
        long matchedTagCount = eligibleTags.stream()
                .map(TagInput::name)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .filter(searchableText::contains)
                .count();
        return new Match(matchedTagCount, eligibleTags.size());
    }

    private static String searchableText(ExternalProductCandidate candidate) {
        return String.join(
                " ",
                candidate.name(),
                nullable(candidate.brand()),
                nullable(candidate.categoryPath())
        ).toLowerCase(Locale.ROOT);
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }

    record Match(long matchedTagCount, int eligibleTagCount) {
    }
}

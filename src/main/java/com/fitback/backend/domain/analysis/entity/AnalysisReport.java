package com.fitback.backend.domain.analysis.entity;

import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "analysis_report")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisReport extends BaseTimeEntity {

    private static final int DEFAULT_MATCH_PERCENTAGE = 70;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "image_url", nullable = false, length = 2048)
    private String imageUrl;

    @Column(name = "match_percentage", nullable = false)
    private Integer matchPercentage;

    @Getter(AccessLevel.NONE)
    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private final List<ReportTag> reportTags = new ArrayList<>();

    private AnalysisReport(Member member, String imageUrl, Integer matchPercentage) {
        this.member = Objects.requireNonNull(member, "member must not be null");
        this.imageUrl = Objects.requireNonNull(imageUrl, "imageUrl must not be null");
        this.matchPercentage = matchPercentage == null ? DEFAULT_MATCH_PERCENTAGE : matchPercentage;
        validateMatchPercentage(this.matchPercentage);
    }

    public static AnalysisReport create(Member member, String imageUrl, Integer matchPercentage) {
        return new AnalysisReport(member, imageUrl, matchPercentage);
    }

    public void changeMatchPercentage(Integer matchPercentage) {
        int nextMatchPercentage = matchPercentage == null
                ? DEFAULT_MATCH_PERCENTAGE
                : matchPercentage;
        validateMatchPercentage(nextMatchPercentage);
        this.matchPercentage = nextMatchPercentage;
    }

    public void addAiSuggestedTag(Tag tag) {
        Objects.requireNonNull(tag, "tag must not be null");
        boolean alreadyExists = reportTags.stream()
                .anyMatch(reportTag -> Objects.equals(reportTag.getTag().getId(), tag.getId()));
        if (!alreadyExists) {
            reportTags.add(ReportTag.suggestedByAi(this, tag));
        }
    }

    public void confirmTags(List<Tag> confirmedTags, Integer matchPercentage) {
        validateMatchPercentage(matchPercentage);
        Objects.requireNonNull(confirmedTags, "confirmedTags must not be null");

        Map<Long, ReportTag> currentTags = reportTags.stream()
                .collect(LinkedHashMap::new,
                        (tags, reportTag) -> tags.put(reportTag.getTag().getId(), reportTag),
                        LinkedHashMap::putAll);
        Set<Long> confirmedTagIds = confirmedTags.stream()
                .map(Tag::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        reportTags.removeIf(reportTag -> !confirmedTagIds.contains(reportTag.getTag().getId()));
        for (Tag tag : confirmedTags) {
            ReportTag reportTag = currentTags.get(tag.getId());
            if (reportTag == null) {
                reportTags.add(ReportTag.addedByMember(this, tag));
            } else {
                reportTag.confirm();
            }
        }
        this.matchPercentage = matchPercentage;
    }

    public List<ReportTag> getReportTags() {
        return List.copyOf(reportTags);
    }

    public List<Tag> getDisplayTags() {
        boolean hasConfirmedTag = reportTags.stream().anyMatch(ReportTag::isConfirmed);
        return reportTags.stream()
                .filter(reportTag -> !hasConfirmedTag || reportTag.isConfirmed())
                .map(ReportTag::getTag)
                .toList();
    }

    private static void validateMatchPercentage(Integer matchPercentage) {
        if (matchPercentage == null || matchPercentage < 0 || matchPercentage > 100) {
            throw new IllegalArgumentException("matchPercentage must be between 0 and 100");
        }
    }
}

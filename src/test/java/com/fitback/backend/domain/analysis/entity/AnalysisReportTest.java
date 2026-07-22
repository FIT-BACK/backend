package com.fitback.backend.domain.analysis.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AnalysisReportTest {

    @Test
    void storesAiSuggestedTagsAsUnconfirmed() {
        AnalysisReport report = AnalysisReport.create(member(), "/uploads/look.jpg", 70);
        Tag minimal = tag(10L, "미니멀");
        Tag wideFit = tag(20L, "와이드핏");

        report.addAiSuggestedTag(minimal);
        report.addAiSuggestedTag(wideFit);

        assertThat(report.getReportTags())
                .extracting(ReportTag::getTag, ReportTag::getSource, ReportTag::isConfirmed)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(minimal, ReportTagSource.AI, false),
                        org.assertj.core.groups.Tuple.tuple(wideFit, ReportTagSource.AI, false)
                );
        assertThat(report.getDisplayTags()).containsExactly(minimal, wideFit);
    }

    @Test
    void confirmsAiTagRemovesDeselectedTagAndAddsMemberTag() {
        AnalysisReport report = AnalysisReport.create(member(), "/uploads/look.jpg", 70);
        Tag minimal = tag(10L, "미니멀");
        Tag wideFit = tag(20L, "와이드핏");
        Tag beige = tag(30L, "베이지톤");
        report.addAiSuggestedTag(minimal);
        report.addAiSuggestedTag(wideFit);

        report.confirmTags(List.of(minimal, beige), 85);

        assertThat(report.getMatchPercentage()).isEqualTo(85);
        assertThat(report.getReportTags())
                .extracting(ReportTag::getTag, ReportTag::getSource, ReportTag::isConfirmed)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(minimal, ReportTagSource.AI, true),
                        org.assertj.core.groups.Tuple.tuple(beige, ReportTagSource.USER, true)
                );
        assertThat(report.getDisplayTags()).containsExactly(minimal, beige);
    }

    @Test
    void storesDuplicateNewlyConfirmedTagOnlyOnce() {
        AnalysisReport report = AnalysisReport.create(member(), "/uploads/look.jpg", 70);
        Tag beige = tag(30L, "베이지톤");

        report.confirmTags(List.of(beige, beige), 85);

        assertThat(report.getReportTags())
                .extracting(ReportTag::getTag)
                .containsExactly(beige);
    }

    @Test
    void rejectsMatchPercentageOutsideRange() {
        AnalysisReport report = AnalysisReport.create(member(), "/uploads/look.jpg", 70);

        assertThatThrownBy(() -> report.changeMatchPercentage(101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Member member() {
        return Member.create("member@example.com", "주녁", "password", LoginProvider.EMAIL);
    }

    private Tag tag(Long id, String name) {
        Tag tag = Tag.create(name, TagType.DETAIL);
        ReflectionTestUtils.setField(tag, "id", id);
        return tag;
    }
}

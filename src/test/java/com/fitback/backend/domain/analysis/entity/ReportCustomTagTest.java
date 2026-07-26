package com.fitback.backend.domain.analysis.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import org.junit.jupiter.api.Test;

class ReportCustomTagTest {

    @Test
    void normalizesDisplayAndComparisonNames() {
        AnalysisReport report = AnalysisReport.create(member(), "/uploads/look.jpg", 70);

        ReportCustomTag tag = ReportCustomTag.create(report, " ＭＩＮＩＭＡＬ ");

        assertThat(tag.getDisplayName()).isEqualTo("MINIMAL");
        assertThat(tag.getNormalizedName()).isEqualTo("minimal");
        assertThat(tag.getReport()).isSameAs(report);
    }

    @Test
    void rejectsBlankOrOverlongNames() {
        AnalysisReport report = AnalysisReport.create(member(), "/uploads/look.jpg", 70);

        assertThatThrownBy(() -> ReportCustomTag.create(report, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportCustomTag.create(report, "a".repeat(51)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Member member() {
        return Member.create("member@example.com", "주녁", "password", LoginProvider.EMAIL);
    }
}

package com.fitback.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.recommendation.service.model.RecommendationInputSnapshot;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RecommendationInputSnapshotFactoryTest {

    private final RecommendationInputSnapshotFactory factory =
            new RecommendationInputSnapshotFactory();

    @Test
    void capturesCanonicalKnownCustomTagsAndMatchPercentage() {
        AnalysisReport report = report();
        Tag later = tag(20L, "와이드핏");
        Tag earlier = tag(10L, "미니멀");
        report.confirmRecommendationInput(
                List.of(later, earlier),
                List.of(" 출근룩 "),
                85
        );

        RecommendationInputSnapshot snapshot = factory.from(report, 1L);

        assertThat(snapshot.tagKeys())
                .containsExactly("TAG:10", "TAG:20", "CUSTOM:출근룩");
        assertThat(snapshot.tagNames())
                .containsExactly("미니멀", "와이드핏", "출근룩");
        assertThat(snapshot.matchPercentage()).isEqualTo(85);
        assertThat(factory.matches(report, snapshot)).isTrue();
    }

    @Test
    void detectsMatchPercentageChangeEvenWhenTagsAreSame() {
        AnalysisReport report = report();
        Tag tag = tag(10L, "미니멀");
        report.confirmRecommendationInput(List.of(tag), List.of("출근룩"), 70);
        RecommendationInputSnapshot snapshot = factory.from(report, 1L);

        report.confirmRecommendationInput(List.of(tag), List.of("출근룩"), 80);

        assertThat(factory.matches(report, snapshot)).isFalse();
    }

    private AnalysisReport report() {
        Member member = Member.create(
                "member@example.com",
                "주녁",
                "password",
                LoginProvider.EMAIL
        );
        AnalysisReport report = AnalysisReport.create(member, "/uploads/look.jpg", 70);
        ReflectionTestUtils.setField(report, "id", 501L);
        return report;
    }

    private Tag tag(Long id, String name) {
        Tag tag = Tag.create(name, TagType.DETAIL);
        ReflectionTestUtils.setField(tag, "id", id);
        return tag;
    }
}

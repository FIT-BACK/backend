package com.fitback.backend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.closet.entity.ClosetSave;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.entity.MemberRole;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.recommendation.entity.RecommendedItem;
import com.fitback.backend.domain.trend.entity.TrendContent;
import com.fitback.backend.domain.trend.entity.TrendTag;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntityInvariantTest {

    @Test
    void analysisReportUsesDefaultWhenChangedPercentageIsNull() {
        AnalysisReport report = AnalysisReport.create(member(), "https://example.com/report.jpg", 85);

        report.changeMatchPercentage(null);

        assertThat(report.getMatchPercentage()).isEqualTo(70);
    }

    @Test
    void memberRejectsNullRequiredValuesImmediately() {
        assertThatThrownBy(() -> Member.create(null, "nickname", "password", LoginProvider.EMAIL))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Member.create("member@example.com", null, "password", LoginProvider.EMAIL))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Member.create("member@example.com", "nickname", "password", null))
                .isInstanceOf(NullPointerException.class);

        Member member = member();
        assertThatThrownBy(() -> member.changeNickname(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> member.changeRole(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void trendContentRejectsNullRequiredValuesImmediately() {
        Member member = member();
        assertThatThrownBy(() -> TrendContent.create(null, "https://example.com/trend.jpg", null, member))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TrendContent.create("title", null, null, member))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TrendContent.create("title", "https://example.com/trend.jpg", null, null))
                .isInstanceOf(NullPointerException.class);

        TrendContent content = TrendContent.create("title", "https://example.com/trend.jpg", null, member);
        assertThatThrownBy(() -> content.changeContent(null, "https://example.com/changed.jpg", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> content.changeContent("changed", null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void productRejectsNegativePriceOnCreateAndChange() {
        assertThatThrownBy(() -> product(-1)).isInstanceOf(IllegalArgumentException.class);

        Product product = product(10_000);
        assertThatThrownBy(() -> product.changePrice(-1, 10_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(product.getPrice()).isEqualTo(10_000);
    }

    @Test
    void recommendedItemRejectsNullRequiredValuesImmediately() {
        AnalysisReport report = AnalysisReport.create(member(), "https://example.com/report.jpg", 85);
        Product product = product(10_000);

        assertThatThrownBy(() -> RecommendedItem.create(null, product, 1, "TOP", 90, true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RecommendedItem.create(report, null, 1, "TOP", 90, true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RecommendedItem.create(report, product, null, "TOP", 90, true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RecommendedItem.create(report, product, 1, null, 90, true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RecommendedItem.create(report, product, 1, "TOP", null, true))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void closetSaveAndTrendTagDeclareCompositeUniqueConstraints() {
        assertThat(uniqueColumns(ClosetSave.class))
                .contains(List.of("member_id", "target_type", "target_id"));
        assertThat(uniqueColumns(TrendTag.class))
                .contains(List.of("trend_id", "tag_id"));
    }

    private static Member member() {
        Member member = Member.create("member@example.com", "nickname", "password", LoginProvider.EMAIL);
        member.changeRole(MemberRole.USER);
        return member;
    }

    private static Product product(int price) {
        return Product.create(
                "external-1",
                "product",
                "brand",
                "seller",
                price,
                10_000,
                "TOP",
                "SUMMER",
                "UNISEX",
                "https://example.com/product",
                "https://example.com/product.jpg",
                "test"
        );
    }

    private static List<List<String>> uniqueColumns(Class<?> entityType) {
        return Arrays.stream(entityType.getAnnotation(Table.class).uniqueConstraints())
                .map(constraint -> List.of(constraint.columnNames()))
                .toList();
    }
}

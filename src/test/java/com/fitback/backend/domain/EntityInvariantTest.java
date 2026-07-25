package com.fitback.backend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.closet.entity.ClosetSave;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.entity.MemberRole;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProductStorageMode;
import com.fitback.backend.domain.product.service.model.ProviderIdentityType;
import com.fitback.backend.domain.recommendation.entity.RecommendedItem;
import com.fitback.backend.domain.trend.entity.TrendContent;
import com.fitback.backend.domain.trend.entity.TrendTag;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
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
        assertThatThrownBy(() -> product(new BigDecimal("-1.00")))
                .isInstanceOf(IllegalArgumentException.class);

        Product product = product(new BigDecimal("10000.00"));
        assertThatThrownBy(() -> product.refreshSnapshot(
                "product",
                "brand",
                "seller",
                ProductCategory.TOP,
                "https://example.com/product.jpg",
                null,
                new BigDecimal("-1.00"),
                null,
                "KRW",
                Instant.parse("2026-07-24T00:00:00Z"),
                "https://example.com/product",
                null,
                ProductAvailability.AVAILABLE,
                Instant.parse("2026-07-24T01:00:00Z")
        ))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(product.getCurrentPrice()).isEqualByComparingTo("10000.00");
    }

    @Test
    void recommendedItemRejectsNullRequiredValuesImmediately() {
        AnalysisReport report = AnalysisReport.create(member(), "https://example.com/report.jpg", 85);
        Product product = product(new BigDecimal("10000.00"));

        assertThatThrownBy(() -> RecommendedItem.create(
                null,
                product,
                1,
                1,
                ProductCategory.TOP,
                new BigDecimal("90.00"),
                new BigDecimal("90.00"),
                "SIMILARITY_V1",
                List.of("HIGH_SIMILARITY")
        ))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RecommendedItem.create(
                report,
                null,
                1,
                1,
                ProductCategory.TOP,
                new BigDecimal("90.00"),
                new BigDecimal("90.00"),
                "SIMILARITY_V1",
                List.of("HIGH_SIMILARITY")
        ))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RecommendedItem.create(
                report,
                product,
                1,
                6,
                ProductCategory.TOP,
                new BigDecimal("90.00"),
                new BigDecimal("90.00"),
                "SIMILARITY_V1",
                List.of("HIGH_SIMILARITY")
        ))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecommendedItem.create(
                report,
                product,
                1,
                1,
                ProductCategory.TOP,
                new BigDecimal("101.00"),
                new BigDecimal("101.00"),
                "SIMILARITY_V1",
                List.of("HIGH_SIMILARITY")
        ))
                .isInstanceOf(IllegalArgumentException.class);
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

    private static Product product(BigDecimal price) {
        return Product.createProviderProduct(
                "test",
                ProviderIdentityType.PROVIDER_KEY,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                null,
                "external-1",
                null,
                "merchant-1",
                ProductStorageMode.SNAPSHOT,
                "product",
                "brand",
                "seller",
                ProductCategory.TOP,
                "https://example.com/product.jpg",
                null,
                price,
                null,
                "KRW",
                Instant.parse("2026-07-24T00:00:00Z"),
                "https://example.com/product",
                null,
                ProductAvailability.AVAILABLE,
                Instant.parse("2026-07-24T01:00:00Z")
        );
    }

    private static List<List<String>> uniqueColumns(Class<?> entityType) {
        return Arrays.stream(entityType.getAnnotation(Table.class).uniqueConstraints())
                .map(constraint -> List.of(constraint.columnNames()))
                .toList();
    }
}

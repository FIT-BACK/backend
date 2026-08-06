package com.fitback.backend.domain.recommendation.entity;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.global.entity.BaseCreateTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Getter
@Entity
@Table(
        name = "recommended_item",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "UK_RECOMMENDED_REPORT_PRODUCT",
                        columnNames = {"report_id", "product_id"}
                ),
                @UniqueConstraint(
                        name = "UK_RECOMMENDED_REPORT_CATEGORY_RANK",
                        columnNames = {"report_id", "category", "rank_no"}
                )
        },
        indexes = @Index(
                name = "IDX_RECOMMENDED_REPORT_CATEGORY_SCORE",
                columnList = "report_id, category, final_score, product_id"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecommendedItem extends BaseCreateTimeEntity {

    private static final int MAX_RANK = 10;
    private static final int MAX_REASON_CODES_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "recommended_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AnalysisReport report;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "input_revision", nullable = false)
    private Integer inputRevision;

    @Column(name = "rank_no", nullable = false)
    private Integer rankNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private ProductCategory category;

    @Column(name = "similarity_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal similarityScore;

    @Column(name = "final_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal finalScore;

    @Column(name = "score_version", nullable = false, length = 30)
    private String scoreVersion;

    @Column(name = "reason_codes", nullable = false, length = MAX_REASON_CODES_LENGTH)
    private String reasonCodes;

    private RecommendedItem(
            AnalysisReport report,
            Product product,
            Integer inputRevision,
            Integer rankNo,
            ProductCategory category,
            BigDecimal similarityScore,
            BigDecimal finalScore,
            String scoreVersion,
            List<String> reasonCodes
    ) {
        this.report = Objects.requireNonNull(report, "report must not be null");
        this.product = Objects.requireNonNull(product, "product must not be null");
        this.inputRevision = requirePositive(inputRevision, "inputRevision");
        this.rankNo = requireRank(rankNo);
        this.category = Objects.requireNonNull(category, "category must not be null");
        this.similarityScore = requireScore(similarityScore, "similarityScore");
        this.finalScore = requireScore(finalScore, "finalScore");
        this.scoreVersion = requireNonBlank(scoreVersion, "scoreVersion");
        this.reasonCodes = serializeReasonCodes(reasonCodes);
    }

    public static RecommendedItem create(
            AnalysisReport report,
            Product product,
            Integer inputRevision,
            Integer rankNo,
            ProductCategory category,
            BigDecimal similarityScore,
            BigDecimal finalScore,
            String scoreVersion,
            List<String> reasonCodes
    ) {
        return new RecommendedItem(
                report,
                product,
                inputRevision,
                rankNo,
                category,
                similarityScore,
                finalScore,
                scoreVersion,
                reasonCodes
        );
    }

    public List<String> getReasonCodeList() {
        if (reasonCodes.isEmpty()) {
            return List.of();
        }
        return List.of(reasonCodes.split(","));
    }

    private static Integer requirePositive(Integer value, String fieldName) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static Integer requireRank(Integer rankNo) {
        if (rankNo == null || rankNo < 1 || rankNo > MAX_RANK) {
            throw new IllegalArgumentException("rankNo must be between 1 and " + MAX_RANK);
        }
        return rankNo;
    }

    private static BigDecimal requireScore(BigDecimal score, String fieldName) {
        Objects.requireNonNull(score, fieldName + " must not be null");
        BigDecimal normalized = score.setScale(2, RoundingMode.HALF_UP);
        if (normalized.compareTo(BigDecimal.ZERO) < 0
                || normalized.compareTo(new BigDecimal("100.00")) > 0) {
            throw new IllegalArgumentException(fieldName + " must be between 0 and 100");
        }
        return normalized;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static String serializeReasonCodes(List<String> reasonCodes) {
        Objects.requireNonNull(reasonCodes, "reasonCodes must not be null");
        String serialized = reasonCodes.stream()
                .map(reasonCode -> requireNonBlank(reasonCode, "reasonCode"))
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        if (serialized.isEmpty() || serialized.length() > MAX_REASON_CODES_LENGTH) {
            throw new IllegalArgumentException("reasonCodes length is invalid");
        }
        return serialized;
    }
}

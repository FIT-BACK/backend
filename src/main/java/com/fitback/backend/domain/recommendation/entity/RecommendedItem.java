package com.fitback.backend.domain.recommendation.entity;

import com.fitback.backend.global.entity.BaseCreateTimeEntity;
import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.product.entity.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "recommended_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecommendedItem extends BaseCreateTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "recommend_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    private AnalysisReport report;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "`rank`", nullable = false)
    private Integer rank;

    @Column(name = "category", nullable = false, length = 50)
    private String category;

    @Column(name = "similarity_score", nullable = false)
    private Integer similarityScore;

    @Column(name = "is_value_match", nullable = false)
    private Boolean valueMatch;

    private RecommendedItem(
            AnalysisReport report,
            Product product,
            Integer rank,
            String category,
            Integer similarityScore,
            Boolean valueMatch
    ) {
        this.report = report;
        this.product = product;
        this.rank = rank;
        this.category = category;
        this.similarityScore = similarityScore;
        this.valueMatch = valueMatch != null && valueMatch;
    }

    public static RecommendedItem create(
            AnalysisReport report,
            Product product,
            Integer rank,
            String category,
            Integer similarityScore,
            Boolean valueMatch
    ) {
        return new RecommendedItem(report, product, rank, category, similarityScore, valueMatch);
    }
}

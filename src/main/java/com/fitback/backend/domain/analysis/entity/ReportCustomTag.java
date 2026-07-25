package com.fitback.backend.domain.analysis.entity;

import com.fitback.backend.global.entity.BaseCreateTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Getter
@Entity
@Table(
        name = "report_custom_tag",
        uniqueConstraints = @UniqueConstraint(
                name = "UK_REPORT_CUSTOM_TAG_NAME",
                columnNames = {"report_id", "normalized_name"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportCustomTag extends BaseCreateTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_custom_tag_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AnalysisReport report;

    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    @Column(name = "normalized_name", nullable = false, length = 50)
    private String normalizedName;

    private ReportCustomTag(AnalysisReport report, String displayName) {
        this.report = Objects.requireNonNull(report, "report must not be null");
        this.displayName = normalizeDisplayName(displayName);
        this.normalizedName = this.displayName.toLowerCase(Locale.ROOT);
    }

    public static ReportCustomTag create(AnalysisReport report, String displayName) {
        return new ReportCustomTag(report, displayName);
    }

    private static String normalizeDisplayName(String value) {
        String normalized = Normalizer.normalize(
                Objects.requireNonNull(value, "displayName must not be null").trim(),
                Normalizer.Form.NFKC
        );
        if (normalized.isBlank() || normalized.length() > 50) {
            throw new IllegalArgumentException(
                    "custom tag name must be between 1 and 50 characters"
            );
        }
        return normalized;
    }
}

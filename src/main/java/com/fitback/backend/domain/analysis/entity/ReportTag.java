package com.fitback.backend.domain.analysis.entity;

import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.global.entity.BaseCreateTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "report_tag")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportTag extends BaseCreateTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_tag_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    private AnalysisReport report;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tag_id", nullable = false)
    private Tag tag;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private ReportTagSource source;

    @Column(name = "is_confirmed", nullable = false)
    private Boolean confirmed;

    private ReportTag(AnalysisReport report, Tag tag, ReportTagSource source, boolean confirmed) {
        this.report = report;
        this.tag = tag;
        this.source = source;
        this.confirmed = confirmed;
    }

    public static ReportTag suggestedByAi(AnalysisReport report, Tag tag) {
        return new ReportTag(report, tag, ReportTagSource.AI, false);
    }

    public static ReportTag addedByMember(AnalysisReport report, Tag tag) {
        return new ReportTag(report, tag, ReportTagSource.USER, true);
    }

    public void confirm() {
        this.confirmed = true;
    }

    public boolean isConfirmed() {
        return Boolean.TRUE.equals(confirmed);
    }
}

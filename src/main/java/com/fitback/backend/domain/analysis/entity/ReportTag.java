package com.fitback.backend.domain.analysis.entity;

import com.fitback.backend.global.entity.BaseCreateTimeEntity;
import com.fitback.backend.domain.tag.entity.Tag;
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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

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
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AnalysisReport report;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tag_id", nullable = false)
    private Tag tag;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private ReportTagSource source;

    @Column(name = "is_confirmed", nullable = false)
    private Boolean confirmed = false;


    private ReportTag(AnalysisReport report, Tag tag, ReportTagSource source) {
        this.report = report;
        this.tag = tag;
        this.source = source;
    }

    public static ReportTag create(AnalysisReport report, Tag tag, ReportTagSource source) {
        return new ReportTag(report, tag, source);
    }

    public void confirm() {
        this.confirmed = true;
    }
}

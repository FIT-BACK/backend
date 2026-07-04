package com.fitback.backend.domain.analysis.entity;

import com.fitback.backend.domain.member.entity.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "analysis_report")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "image_url", nullable = false, length = 2048)
    private String imageUrl;

    @Column(name = "match_percentage", nullable = false)
    private Integer matchPercentage = 70;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private AnalysisReport(Member member, String imageUrl, Integer matchPercentage) {
        this.member = member;
        this.imageUrl = imageUrl;
        this.matchPercentage = matchPercentage;
    }

    public static AnalysisReport create(Member member, String imageUrl, Integer matchPercentage) {
        return new AnalysisReport(member, imageUrl, matchPercentage);
    }

    public void changeMatchPercentage(Integer matchPercentage) {
        this.matchPercentage = matchPercentage;
        this.updatedAt = LocalDateTime.now();
    }
}

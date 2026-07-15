package com.fitback.backend.domain.analysis.entity;

import com.fitback.backend.global.entity.BaseTimeEntity;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "analysis_report")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisReport extends BaseTimeEntity {

    private static final int DEFAULT_MATCH_PERCENTAGE = 70;

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
    private Integer matchPercentage;

    private AnalysisReport(Member member, String imageUrl, Integer matchPercentage) {
        this.member = member;
        this.imageUrl = imageUrl;
        this.matchPercentage = matchPercentageOrDefault(matchPercentage);
    }

    public static AnalysisReport create(Member member, String imageUrl, Integer matchPercentage) {
        return new AnalysisReport(member, imageUrl, matchPercentage);
    }

    public void changeMatchPercentage(Integer matchPercentage) {
        this.matchPercentage = matchPercentageOrDefault(matchPercentage);
    }

    private static int matchPercentageOrDefault(Integer matchPercentage) {
        return matchPercentage == null ? DEFAULT_MATCH_PERCENTAGE : matchPercentage;
    }
}

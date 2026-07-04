package com.fitback.backend.domain.trend.entity;

import com.fitback.backend.global.entity.BaseCreateTimeEntity;
import com.fitback.backend.domain.tag.entity.Tag;
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
@Table(name = "trend_tag")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrendTag extends BaseCreateTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "trend_tag_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trend_id", nullable = false)
    private TrendContent trend;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tag_id", nullable = false)
    private Tag tag;


    private TrendTag(TrendContent trend, Tag tag) {
        this.trend = trend;
        this.tag = tag;
    }

    public static TrendTag create(TrendContent trend, Tag tag) {
        return new TrendTag(trend, tag);
    }
}

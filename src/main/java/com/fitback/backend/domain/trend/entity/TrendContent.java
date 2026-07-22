package com.fitback.backend.domain.trend.entity;

import com.fitback.backend.global.entity.BaseTimeEntity;
import com.fitback.backend.domain.member.entity.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "trend_content")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrendContent extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "trend_id")
    private Long id;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "image_url", nullable = false, length = 2048)
    private String imageUrl;

    @Lob
    @Column(name = "description")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private Member createdBy;


    private TrendContent(String title, String imageUrl, String description, Member createdBy) {
        this.title = Objects.requireNonNull(title, "title must not be null");
        this.imageUrl = Objects.requireNonNull(imageUrl, "imageUrl must not be null");
        this.description = description;
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
    }

    public static TrendContent create(String title, String imageUrl, String description, Member createdBy) {
        return new TrendContent(title, imageUrl, description, createdBy);
    }

    public void changeContent(String title, String imageUrl, String description) {
        this.title = Objects.requireNonNull(title, "title must not be null");
        this.imageUrl = Objects.requireNonNull(imageUrl, "imageUrl must not be null");
        this.description = description;
    }
}

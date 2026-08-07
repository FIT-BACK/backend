package com.fitback.backend.domain.tag.entity;

import com.fitback.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "tag",
        uniqueConstraints = @UniqueConstraint(name = "UK_TAG_NAME", columnNames = "tag_name")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tag extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tag_id")
    private Long id;

    @Column(name = "tag_name", nullable = false, length = 50)
    private String tagName;

    @Enumerated(EnumType.STRING)
    @Column(name = "tag_type", nullable = false, length = 30)
    private TagType tagType;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "tag_target_clothing",
            joinColumns = @JoinColumn(name = "tag_id")
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "target_clothing", nullable = false, length = 20)
    private Set<TagTargetClothing> targetClothing = new LinkedHashSet<>();

    private Tag(String tagName, TagType tagType) {
        this.tagName = tagName;
        this.tagType = tagType;
    }

    private Tag(
            String tagName,
            TagType tagType,
            Collection<TagTargetClothing> targetClothing
    ) {
        this(tagName, tagType);
        this.targetClothing.addAll(targetClothing);
    }

    public static Tag create(String tagName, TagType tagType) {
        return new Tag(tagName, tagType);
    }

    public static Tag create(
            String tagName,
            TagType tagType,
            Collection<TagTargetClothing> targetClothing
    ) {
        return new Tag(tagName, tagType, targetClothing);
    }

    public Set<TagTargetClothing> getTargetClothing() {
        return Collections.unmodifiableSet(targetClothing);
    }

    public void changeTagName(String tagName) {
        this.tagName = tagName;
    }
}

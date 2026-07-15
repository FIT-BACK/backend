package com.fitback.backend.domain.lookbook.entity;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Getter
@Entity
@Table(
        name = "lookbook_tag",
        uniqueConstraints = @UniqueConstraint(
                name = "UK_LOOKBOOK_TAG_LOOKBOOK_ID_TAG_ID",
                columnNames = {"lookbook_id", "tag_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LookbookTag extends BaseCreateTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "lookbook_tag_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lookbook_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Lookbook lookbook;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tag_id", nullable = false)
    private Tag tag;


    private LookbookTag(Lookbook lookbook, Tag tag) {
        this.lookbook = lookbook;
        this.tag = tag;
    }

    public static LookbookTag create(Lookbook lookbook, Tag tag) {
        return new LookbookTag(lookbook, tag);
    }
}

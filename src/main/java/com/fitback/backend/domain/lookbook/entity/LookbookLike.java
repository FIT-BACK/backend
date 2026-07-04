package com.fitback.backend.domain.lookbook.entity;

import com.fitback.backend.global.entity.BaseCreateTimeEntity;
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
@Table(name = "lookbook_like")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LookbookLike extends BaseCreateTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "like_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lookbook_id", nullable = false)
    private Lookbook lookbook;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;


    private LookbookLike(Lookbook lookbook, Member member) {
        this.lookbook = lookbook;
        this.member = member;
    }

    public static LookbookLike create(Lookbook lookbook, Member member) {
        return new LookbookLike(lookbook, member);
    }
}

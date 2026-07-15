package com.fitback.backend.domain.closet.entity;

import com.fitback.backend.global.entity.BaseCreateTimeEntity;
import com.fitback.backend.domain.member.entity.Member;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Getter
@Entity
@Table(
        name = "closet_save",
        uniqueConstraints = @UniqueConstraint(
                name = "UK_CLOSET_SAVE_MEMBER_ID_TARGET_TYPE_TARGET_ID",
                columnNames = {"member_id", "target_type", "target_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClosetSave extends BaseCreateTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "save_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    private ClosetTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;


    private ClosetSave(Member member, ClosetTargetType targetType, Long targetId) {
        this.member = member;
        this.targetType = targetType;
        this.targetId = targetId;
    }

    public static ClosetSave create(Member member, ClosetTargetType targetType, Long targetId) {
        return new ClosetSave(member, targetType, targetId);
    }
}

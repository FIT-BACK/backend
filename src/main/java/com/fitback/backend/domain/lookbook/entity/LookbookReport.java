package com.fitback.backend.domain.lookbook.entity;

import com.fitback.backend.domain.member.entity.Member;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "lookbook_report",
        uniqueConstraints = @UniqueConstraint(
                name = "UK_LOOKBOOK_REPORT_LOOKBOOK_ID_MEMBER_ID",
                columnNames = {"lookbook_id", "member_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LookbookReport extends BaseCreateTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lookbook_id", nullable = false)
    private Lookbook lookbook;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 50)
    private LookbookReportReason reason;

    private LookbookReport(
            Lookbook lookbook,
            Member member,
            LookbookReportReason reason
    ) {
        this.lookbook = lookbook;
        this.member = member;
        this.reason = reason;
    }

    public static LookbookReport create(
            Lookbook lookbook,
            Member member,
            LookbookReportReason reason
    ) {
        return new LookbookReport(lookbook, member, reason);
    }
}

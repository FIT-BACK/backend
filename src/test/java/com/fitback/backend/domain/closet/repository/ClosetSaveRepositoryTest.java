package com.fitback.backend.domain.closet.repository;

import static com.fitback.backend.domain.lookbook.LookbookImageFixtures.readyImage;
import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.closet.entity.ClosetSave;
import com.fitback.backend.domain.closet.entity.ClosetTargetType;
import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImagePurpose;
import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.trend.entity.TrendContent;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@DataJpaTest
class ClosetSaveRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private ClosetSaveRepository closetSaveRepository;

    //삭제되었거나 현재 회원이 조회할 수 없는 저장 대상은 집계에서 제외
    @Test
    void countDisplayableByMemberIdExcludesInvalidTargets() {
        Member member = persistMember("member@fitback.com", "member");
        Member otherMember = persistMember("other@fitback.com", "other");

        TrendContent trend = em.persist(TrendContent.create(
                "트렌드",
                "https://example.com/trend.jpg",
                null,
                member
        ));

        Lookbook activeLookbook = persistLookbook(member, "active-lookbook");
        Lookbook deletedLookbook = persistLookbook(member, "deleted-lookbook");
        deletedLookbook.softDelete();

        AnalysisReport activeReport = em.persist(AnalysisReport.create(
                member,
                "https://example.com/active-report.jpg",
                80
        ));
        AnalysisReport deletedReport = em.persist(AnalysisReport.create(
                member,
                "https://example.com/deleted-report.jpg",
                80
        ));
        deletedReport.softDelete(Instant.parse("2026-08-13T00:00:00Z"));

        AnalysisReport otherMemberReport = em.persist(AnalysisReport.create(
                otherMember,
                "https://example.com/other-report.jpg",
                80
        ));

        persistSave(member, ClosetTargetType.TREND, trend.getId());
        persistSave(member, ClosetTargetType.LOOKBOOK, activeLookbook.getId());
        persistSave(member, ClosetTargetType.LOOKBOOK, deletedLookbook.getId());
        persistSave(member, ClosetTargetType.ANALYSIS_REPORT, activeReport.getId());
        persistSave(member, ClosetTargetType.ANALYSIS_REPORT, deletedReport.getId());
        persistSave(member, ClosetTargetType.ANALYSIS_REPORT, otherMemberReport.getId());
        persistSave(member, ClosetTargetType.TREND, 999_999L);

        em.flush();
        em.clear();

        long result = closetSaveRepository.countDisplayableByMemberId(member.getId());

        assertThat(result).isEqualTo(3L);
    }

    private Member persistMember(String email, String nickname) {
        return em.persist(Member.create(
                email,
                nickname,
                "encodedPw",
                LoginProvider.EMAIL
        ));
    }

    private Lookbook persistLookbook(Member member, String imageId) {
        Image originalImage = em.persist(readyImage(
                imageId,
                member,
                ImagePurpose.LOOKBOOK
        ));
        return em.persist(Lookbook.create(
                member,
                originalImage,
                null,
                null,
                null
        ));
    }

    private void persistSave(
            Member member,
            ClosetTargetType targetType,
            Long targetId
    ) {
        em.persist(ClosetSave.create(member, targetType, targetId));
    }
}

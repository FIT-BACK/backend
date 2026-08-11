package com.fitback.backend.domain.analysis.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.external.aitag.GarmentPiece;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@DataJpaTest
class AnalysisReportRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void persistsGarmentPieceAsEnumName() {
        Member member = entityManager.persist(member("garment-piece@example.com"));
        AnalysisReport report = entityManager.persist(AnalysisReport.create(
                member,
                "https://example.com/analysis.jpg",
                50,
                GarmentPiece.DRESS
        ));
        entityManager.flush();
        entityManager.clear();

        AnalysisReport persisted = entityManager.find(AnalysisReport.class, report.getId());

        assertThat(persisted.getGarmentPiece()).isEqualTo(GarmentPiece.DRESS);
    }

    @Test
    void readsLegacyReportWithNullGarmentPiece() {
        Member member = entityManager.persist(member("legacy-report@example.com"));
        AnalysisReport report = entityManager.persist(AnalysisReport.create(
                member,
                "https://example.com/legacy-analysis.jpg",
                70
        ));
        entityManager.flush();
        entityManager.clear();

        AnalysisReport persisted = entityManager.find(AnalysisReport.class, report.getId());

        assertThat(persisted.getGarmentPiece()).isNull();
    }

    private static Member member(String email) {
        return Member.create(email, "nickname", "password", LoginProvider.EMAIL);
    }
}

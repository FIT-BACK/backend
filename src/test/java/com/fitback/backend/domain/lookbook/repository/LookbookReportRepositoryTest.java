package com.fitback.backend.domain.lookbook.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.lookbook.entity.LookbookModerationStatus;
import com.fitback.backend.domain.lookbook.entity.LookbookReport;
import com.fitback.backend.domain.lookbook.entity.LookbookReportReason;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class LookbookReportRepositoryTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private LookbookRepository lookbookRepository;

    @Autowired
    private LookbookReportRepository lookbookReportRepository;

    @Test
    void savesLookbookReportWithRequiredFields() {
        Member owner = createMember("report-owner@fitback.com", "report-owner");
        Member reporter = createMember("reporter@fitback.com", "reporter");
        Lookbook lookbook = createLookbook(owner);
        entityManager.persist(owner);
        entityManager.persist(reporter);
        entityManager.persist(lookbook);

        LookbookReport report = lookbookReportRepository.saveAndFlush(LookbookReport.create(
                lookbook,
                reporter,
                LookbookReportReason.INAPPROPRIATE_IMAGE
        ));

        assertThat(report.getId()).isNotNull();
        assertThat(report.getLookbook().getId()).isEqualTo(lookbook.getId());
        assertThat(report.getMember().getId()).isEqualTo(reporter.getId());
        assertThat(report.getReason()).isEqualTo(LookbookReportReason.INAPPROPRIATE_IMAGE);
        assertThat(report.getCreatedAt()).isNotNull();
        assertThat(lookbook.getReportCount()).isZero();
        assertThat(lookbook.getModerationStatus()).isEqualTo(LookbookModerationStatus.VISIBLE);
        assertThat(lookbook.getAutoHiddenAt()).isNull();
    }

    @Test
    void rejectsDuplicateReportBySameMemberAndLookbook() {
        Member owner = createMember("duplicate-owner@fitback.com", "duplicate-owner");
        Member reporter = createMember("duplicate-reporter@fitback.com", "duplicate-reporter");
        Lookbook lookbook = createLookbook(owner);
        entityManager.persist(owner);
        entityManager.persist(reporter);
        entityManager.persist(lookbook);
        lookbookReportRepository.saveAndFlush(LookbookReport.create(
                lookbook,
                reporter,
                LookbookReportReason.SPAM_OR_ADVERTISEMENT
        ));

        assertThatThrownBy(() -> lookbookReportRepository.saveAndFlush(LookbookReport.create(
                lookbook,
                reporter,
                LookbookReportReason.OTHER
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void thirdReportAutoHidesLookbookFromFeedButKeepsDetailAccessible() {
        Member owner = createMember("hidden-owner@fitback.com", "hidden-owner");
        Lookbook lookbook = createLookbook(owner);
        entityManager.persist(owner);
        entityManager.persist(lookbook);
        entityManager.flush();

        incrementReportAndApplyModeration(lookbook.getId());
        incrementReportAndApplyModeration(lookbook.getId());

        Lookbook beforeThreshold = lookbookRepository.findById(lookbook.getId()).orElseThrow();
        assertThat(beforeThreshold.getReportCount()).isEqualTo(2);
        assertThat(beforeThreshold.getModerationStatus())
                .isEqualTo(LookbookModerationStatus.VISIBLE);
        assertThat(beforeThreshold.getAutoHiddenAt()).isNull();

        incrementReportAndApplyModeration(lookbook.getId());

        Lookbook autoHiddenLookbook = lookbookRepository.findById(lookbook.getId()).orElseThrow();
        assertThat(autoHiddenLookbook.getReportCount()).isEqualTo(3);
        assertThat(autoHiddenLookbook.getModerationStatus())
                .isEqualTo(LookbookModerationStatus.AUTO_HIDDEN);
        assertThat(autoHiddenLookbook.getAutoHiddenAt()).isNotNull();

        List<Lookbook> feed = lookbookRepository
                .findAllByDeletedAtIsNullAndModerationStatusOrderByCreatedAtDescIdDesc(
                        LookbookModerationStatus.VISIBLE,
                        PageRequest.of(0, 20)
                );
        assertThat(feed).isEmpty();
        assertThat(lookbookRepository.findByIdAndDeletedAtIsNull(lookbook.getId())).isPresent();
    }

    private void incrementReportAndApplyModeration(Long lookbookId) {
        assertThat(lookbookRepository.incrementReportCount(lookbookId)).isEqualTo(1);
        lookbookRepository.autoHideReportedLookbook(
                lookbookId,
                3,
                LookbookModerationStatus.VISIBLE,
                LookbookModerationStatus.AUTO_HIDDEN
        );
    }

    private Member createMember(String email, String nickname) {
        return Member.create(email, nickname, "password", LoginProvider.EMAIL);
    }

    private Lookbook createLookbook(Member owner) {
        return Lookbook.create(
                owner,
                "https://s3.example.com/report-original.jpg",
                "https://s3.example.com/report-matched.jpg",
                null,
                null
        );
    }
}

package com.fitback.backend.domain.lookbook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.lookbook.entity.LookbookModerationStatus;
import com.fitback.backend.domain.lookbook.entity.LookbookReport;
import com.fitback.backend.domain.lookbook.entity.LookbookReportReason;
import com.fitback.backend.domain.lookbook.repository.LookbookReportRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LookbookReportCommandServiceTest {

    @Mock
    private LookbookRepository lookbookRepository;

    @Mock
    private LookbookReportRepository lookbookReportRepository;

    @InjectMocks
    private LookbookReportCommandService lookbookReportCommandService;

    private Member owner;
    private Member reporter;
    private Lookbook lookbook;

    @BeforeEach
    void setUp() {
        owner = Member.create("owner@fitback.com", "owner", "password", LoginProvider.EMAIL);
        reporter = Member.create(
                "reporter@fitback.com",
                "reporter",
                "password",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(owner, "id", 1L);
        ReflectionTestUtils.setField(reporter, "id", 2L);

        lookbook = Lookbook.create(
                owner,
                "https://s3.example.com/original.jpg",
                "https://s3.example.com/matched.jpg",
                null,
                null
        );
        ReflectionTestUtils.setField(lookbook, "id", 100L);
    }

    @Test
    void createReportSavesReportAndUpdatesModerationState() {
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L))
                .thenReturn(Optional.of(lookbook));
        when(lookbookReportRepository.saveAndFlush(any(LookbookReport.class)))
                .thenAnswer(invocation -> {
                    LookbookReport report = invocation.getArgument(0);
                    ReflectionTestUtils.setField(report, "id", 101L);
                    return report;
                });
        when(lookbookRepository.incrementReportCount(100L)).thenReturn(1);

        LookbookReport report = lookbookReportCommandService.createReport(
                100L,
                reporter,
                LookbookReportReason.SPAM_OR_ADVERTISEMENT
        );

        assertThat(report.getId()).isEqualTo(101L);
        assertThat(report.getLookbook()).isEqualTo(lookbook);
        assertThat(report.getMember()).isEqualTo(reporter);
        assertThat(report.getReason()).isEqualTo(LookbookReportReason.SPAM_OR_ADVERTISEMENT);
        verify(lookbookRepository).autoHideReportedLookbook(
                100L,
                3,
                LookbookModerationStatus.VISIBLE,
                LookbookModerationStatus.AUTO_HIDDEN
        );
    }

    @Test
    void createReportRejectsOwnersOwnLookbook() {
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L))
                .thenReturn(Optional.of(lookbook));

        assertThatThrownBy(() -> lookbookReportCommandService.createReport(
                100L,
                owner,
                LookbookReportReason.OTHER
        )).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(exception.getMessage()).isEqualTo("본인의 룩북은 신고할 수 없습니다.");
        });
        verify(lookbookReportRepository, never()).saveAndFlush(any());
        verify(lookbookRepository, never()).incrementReportCount(any());
    }

    @Test
    void createReportFailsWhenLookbookIsDeletedOrMissing() {
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lookbookReportCommandService.createReport(
                100L,
                reporter,
                LookbookReportReason.OTHER
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND)
        );
        verify(lookbookReportRepository, never()).saveAndFlush(any());
    }

    @Test
    void createReportFailsWhenReportCountCannotBeUpdated() {
        when(lookbookRepository.findByIdAndDeletedAtIsNull(100L))
                .thenReturn(Optional.of(lookbook));
        when(lookbookReportRepository.saveAndFlush(any(LookbookReport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(lookbookRepository.incrementReportCount(100L)).thenReturn(0);

        assertThatThrownBy(() -> lookbookReportCommandService.createReport(
                100L,
                reporter,
                LookbookReportReason.OTHER
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND)
        );
        verify(lookbookRepository, never()).autoHideReportedLookbook(
                any(),
                any(Integer.class),
                any(),
                any()
        );
    }
}

package com.fitback.backend.domain.lookbook.service;

import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.lookbook.entity.LookbookModerationStatus;
import com.fitback.backend.domain.lookbook.entity.LookbookReport;
import com.fitback.backend.domain.lookbook.entity.LookbookReportReason;
import com.fitback.backend.domain.lookbook.repository.LookbookReportRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LookbookReportCommandService {

    private static final int AUTO_HIDE_REPORT_THRESHOLD = 3;

    private final LookbookRepository lookbookRepository;
    private final LookbookReportRepository lookbookReportRepository;

    // 룩북 신고 엔티티 생성
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LookbookReport createReport(
            Long lookbookId,
            Member member,
            LookbookReportReason reason
    ) {
        // 룩북 유효성 검사 및 조회
        Lookbook lookbook = lookbookRepository.findByIdAndDeletedAtIsNull(lookbookId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "룩북을 찾을 수 없습니다."
                ));

        // 본인 룩북은 신고 불가
        if (Objects.equals(lookbook.getMember().getId(), member.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인의 룩북은 신고할 수 없습니다.");
        }

        // 룩북 생성 후 바로 DB에 저장
        LookbookReport report = LookbookReport.create(lookbook, member, reason);
        LookbookReport savedReport = lookbookReportRepository.saveAndFlush(report);

        // 룩북의 reportCount 증가
        int updatedRows = lookbookRepository.incrementReportCount(lookbookId);
        if (updatedRows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "룩북을 찾을 수 없습니다.");
        }

        // reportCount 가 3보다 커졌다면 룩북 숨김 처리
        lookbookRepository.autoHideReportedLookbook(
                lookbookId,
                AUTO_HIDE_REPORT_THRESHOLD,
                LookbookModerationStatus.VISIBLE,
                LookbookModerationStatus.AUTO_HIDDEN
        );

        return savedReport;
    }
}

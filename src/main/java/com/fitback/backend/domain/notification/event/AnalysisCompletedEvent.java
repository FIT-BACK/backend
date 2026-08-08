package com.fitback.backend.domain.notification.event;

//AI 분석 완료
public record AnalysisCompletedEvent(
        Long reportId,
        Long recipientMemberId
) {
}

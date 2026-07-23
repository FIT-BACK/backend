package com.fitback.backend.domain.image.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ImageLifecycleTest {

    @Test
    void completesAndActivatesAnalysisImage() {
        Image image = image(member(1L), ImagePurpose.ANALYSIS_ORIGINAL);

        image.completeUpload(1024, "image/jpeg", LocalDateTime.of(2026, 7, 22, 9, 0));
        image.activateForAnalysis(1L, Instant.parse("2026-07-22T00:01:00Z"));

        assertThat(image.getStatus()).isEqualTo(ImageStatus.ACTIVE);
        assertThat(image.getPresignedExpiresAt()).isNull();
        assertThat(image.getActivatedAt()).isNotNull();
    }

    @Test
    void rejectsActivationForAnotherOwner() {
        Image image = image(member(1L), ImagePurpose.ANALYSIS_ORIGINAL);
        image.completeUpload(1024, "image/jpeg", LocalDateTime.of(2026, 7, 22, 9, 0));

        assertThatThrownBy(() -> image.activateForAnalysis(
                2L,
                Instant.parse("2026-07-22T00:01:00Z")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsFuturePurposeAndPendingStatusDuringCompatibilityRelease() {
        Image image = image(member(1L), ImagePurpose.ANALYSIS);
        ReflectionTestUtils.setField(image, "status", ImageStatus.PENDING_UPLOAD);

        image.completeUpload(1024, "image/jpeg", LocalDateTime.of(2026, 7, 22, 9, 0));
        image.activateForAnalysis(1L, Instant.parse("2026-07-22T00:01:00Z"));

        assertThat(image.getStatus()).isEqualTo(ImageStatus.ACTIVE);
    }

    @Test
    void rejectsFileLargerThanFiveMebibytes() {
        assertThatThrownBy(() -> Image.createPending(
                "too-large",
                member(1L),
                "prod/images/analysis_original/2026/07/too-large.jpg",
                ImagePurpose.ANALYSIS_ORIGINAL,
                "image/jpeg",
                Image.MAX_FILE_SIZE + 1,
                ImageVisibility.PRIVATE,
                Instant.parse("2026-07-22T00:05:00Z")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private Image image(Member member, ImagePurpose purpose) {
        return Image.createPending(
                "image-id",
                member,
                "prod/images/analysis_original/2026/07/image.jpg",
                purpose,
                "image/jpeg",
                1024,
                ImageVisibility.PRIVATE,
                Instant.parse("2026-07-22T00:05:00Z")
        );
    }

    private Member member(Long id) {
        Member member = Member.create(
                "member@example.com",
                "주녁",
                "password",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }
}

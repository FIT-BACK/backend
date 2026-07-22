package com.fitback.backend.domain.image.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ImageAssetTest {

    @Test
    void completesAndActivatesAnalysisImage() {
        Member member = member(1L);
        ImageAsset image = image(member, ImagePurpose.ANALYSIS);

        image.completeUpload(1024, "image/jpeg", LocalDateTime.of(2026, 7, 22, 9, 0));
        image.activateForAnalysis(1L, Instant.parse("2026-07-22T00:01:00Z"));

        assertThat(image.getAssetStatus()).isEqualTo(ImageAssetStatus.ACTIVE);
        assertThat(image.getActivatedAt()).isNotNull();
    }

    @Test
    void rejectsActivationForAnotherOwner() {
        ImageAsset image = image(member(1L), ImagePurpose.ANALYSIS);
        image.completeUpload(1024, "image/jpeg", LocalDateTime.of(2026, 7, 22, 9, 0));

        assertThatThrownBy(() -> image.activateForAnalysis(
                2L,
                Instant.parse("2026-07-22T00:01:00Z")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsFileLargerThanFiveMebibytes() {
        assertThatThrownBy(() -> ImageAsset.create(
                member(1L),
                ImagePurpose.ANALYSIS,
                "images/analysis/1/too-large.jpg",
                "image/jpeg",
                ImageAsset.MAX_FILE_SIZE_BYTES + 1,
                Instant.parse("2026-07-22T00:05:00Z")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private ImageAsset image(Member member, ImagePurpose purpose) {
        return ImageAsset.create(
                member,
                purpose,
                "images/analysis/1/image.jpg",
                "image/jpeg",
                1024,
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

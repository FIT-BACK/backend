package com.fitback.backend.domain.image.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ImageTest {

    private static final Instant NOW = Instant.parse("2026-07-22T05:00:00Z");

    @Test
    void rejectsFileLargerThanFiveMegabytes() {
        Member owner = Member.create(
                "image-entity@fitback.com",
                "image-entity-user",
                "password",
                LoginProvider.EMAIL
        );

        assertThatThrownBy(() -> Image.createPending(
                "35e7f670-aa08-4c3b-b78a-6705f042be31",
                owner,
                "prod/images/profile/2026/07/35e7f670-aa08-4c3b-b78a-6705f042be31.jpg",
                ImagePurpose.PROFILE,
                "image/jpeg",
                5_242_881,
                ImageVisibility.PRIVATE,
                Instant.parse("2026-07-22T05:05:00Z")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fileSize must be between 1 and 5242880");
    }

    @Test
    void activatesReadyProfileImageOwnedByMember() {
        Member owner = member(1L);
        Image image = readyImage("profile-image", owner, ImagePurpose.PROFILE);

        image.activateForProfile(1L, NOW);

        assertThat(image.getStatus()).isEqualTo(ImageStatus.ACTIVE);
        assertThat(image.getActivatedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsNonProfilePurpose() {
        Member owner = member(1L);
        Image image = readyImage("analysis-image", owner, ImagePurpose.ANALYSIS);

        assertThatThrownBy(() -> image.activateForProfile(1L, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("image purpose must be PROFILE");
    }

    @Test
    void rejectsProfileImageOwnedByAnotherMember() {
        Member owner = member(1L);
        Image image = readyImage("profile-image", owner, ImagePurpose.PROFILE);

        assertThatThrownBy(() -> image.activateForProfile(2L, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("image owner does not match");
    }

    @Test
    void rejectsProfileImageBeforeUploadCompletion() {
        Member owner = member(1L);
        Image image = Image.createPending(
                "profile-image",
                owner,
                "images/profile/1/2026/07/profile-image.jpg",
                ImagePurpose.PROFILE,
                "image/jpeg",
                1024,
                ImageVisibility.PRIVATE,
                NOW.plusSeconds(300)
        );

        assertThatThrownBy(() -> image.activateForProfile(1L, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("image status must be READY");
    }

    private Member member(Long memberId) {
        Member member = Member.create(
                "member-%d@fitback.com".formatted(memberId),
                "member-%d".formatted(memberId),
                "password",
                LoginProvider.EMAIL
        );
        org.springframework.test.util.ReflectionTestUtils.setField(member, "id", memberId);
        return member;
    }

    private Image readyImage(String imageId, Member owner, ImagePurpose purpose) {
        Image image = Image.createPending(
                imageId,
                owner,
                "images/%s/%d/2026/07/%s.jpg".formatted(
                        purpose.name().toLowerCase(),
                        owner.getId(),
                        imageId
                ),
                purpose,
                "image/jpeg",
                1024,
                ImageVisibility.PRIVATE,
                NOW.plusSeconds(300)
        );
        image.completeUpload(1024, "image/jpeg", LocalDateTime.of(2026, 7, 22, 14, 0));
        return image;
    }
}

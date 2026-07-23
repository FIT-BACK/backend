package com.fitback.backend.domain.image.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImagePurpose;
import com.fitback.backend.domain.image.entity.ImageStatus;
import com.fitback.backend.domain.image.entity.ImageVisibility;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class ImageRepositoryTest {

    private static final List<ImageStatus> CLEANUP_STATUSES = List.of(
            ImageStatus.PENDING,
            ImageStatus.PENDING_UPLOAD,
            ImageStatus.READY,
            ImageStatus.REJECTED,
            ImageStatus.DELETE_FAILED
    );

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ImageRepository imageRepository;

    @Test
    void usesCreatedAtWhenRejectedImageHasNoUploadedAt() {
        Member owner = persistOwner("rejected-fallback@fitback.com");
        Image rejected = pendingImage("rejected-fallback", owner);
        rejected.reject(LocalDateTime.of(2026, 7, 24, 0, 0));
        ReflectionTestUtils.setField(rejected, "uploadedAt", null);
        entityManager.persist(rejected);
        entityManager.flush();

        List<Image> candidates = imageRepository.findCleanupCandidates(
                CLEANUP_STATUSES,
                LocalDateTime.now().plusHours(1),
                Instant.parse("2026-07-24T00:00:00Z"),
                null,
                PageRequest.of(0, 10)
        );

        assertThat(candidates).extracting(Image::getId)
                .contains("rejected-fallback");
    }

    @Test
    void retriesDueDeleteFailureWithoutApplyingUploadAgeCutoff() {
        Member owner = persistOwner("delete-retry@fitback.com");
        Image failed = pendingImage("delete-retry", owner);
        failed.claimForDeletion(Instant.parse("2026-07-24T00:00:00Z"));
        failed.markDeleteFailed(Instant.parse("2026-07-24T01:00:00Z"));
        entityManager.persist(failed);
        entityManager.flush();

        List<Image> candidates = imageRepository.findCleanupCandidates(
                CLEANUP_STATUSES,
                LocalDateTime.now().minusDays(1),
                Instant.parse("2026-07-24T01:00:00Z"),
                null,
                PageRequest.of(0, 10)
        );

        assertThat(candidates).extracting(Image::getId)
                .containsExactly("delete-retry");
    }

    @Test
    void excludesDeleteFailureBeforeItsRetryTime() {
        Member owner = persistOwner("future-retry@fitback.com");
        Image failed = pendingImage("future-retry", owner);
        failed.claimForDeletion(Instant.parse("2026-07-24T00:00:00Z"));
        failed.markDeleteFailed(Instant.parse("2026-07-24T02:00:00Z"));
        entityManager.persist(failed);
        entityManager.flush();

        List<Image> candidates = imageRepository.findCleanupCandidates(
                CLEANUP_STATUSES,
                LocalDateTime.now().plusDays(1),
                Instant.parse("2026-07-24T01:00:00Z"),
                null,
                PageRequest.of(0, 10)
        );

        assertThat(candidates).isEmpty();
    }

    @Test
    void findsBothLegacyAndFuturePendingUploadStatuses() {
        Member owner = persistOwner("pending-compatibility@fitback.com");
        Image legacyPending = pendingImage("legacy-pending", owner);
        Image futurePending = pendingImage("future-pending", owner);
        ReflectionTestUtils.setField(futurePending, "status", ImageStatus.PENDING_UPLOAD);
        entityManager.persist(legacyPending);
        entityManager.persist(futurePending);
        entityManager.flush();

        List<Image> candidates = imageRepository.findCleanupCandidates(
                CLEANUP_STATUSES,
                LocalDateTime.now().plusHours(1),
                Instant.parse("2026-07-24T00:00:00Z"),
                null,
                PageRequest.of(0, 10)
        );

        assertThat(candidates).extracting(Image::getId)
                .contains("legacy-pending", "future-pending");
    }

    private Member persistOwner(String email) {
        Member owner = Member.create(
                email,
                "image-owner-" + email.hashCode(),
                "password",
                LoginProvider.EMAIL
        );
        entityManager.persist(owner);
        return owner;
    }

    private Image pendingImage(String id, Member owner) {
        return Image.createPending(
                id,
                owner,
                "images/analysis/%d/2026/07/%s.jpg".formatted(owner.getId(), id),
                ImagePurpose.ANALYSIS,
                "image/jpeg",
                1024,
                ImageVisibility.PRIVATE,
                Instant.parse("2026-07-24T00:05:00Z")
        );
    }
}

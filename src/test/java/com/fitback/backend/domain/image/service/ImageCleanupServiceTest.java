package com.fitback.backend.domain.image.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImagePurpose;
import com.fitback.backend.domain.image.entity.ImageStatus;
import com.fitback.backend.domain.image.entity.ImageVisibility;
import com.fitback.backend.domain.image.repository.ImageRepository;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ImageCleanupServiceTest {

    @Test
    void claimsOnlyImagesWithoutDomainReferences() {
        ImageRepository repository = mock(ImageRepository.class);
        ImageObjectStorage storage = mock(ImageObjectStorage.class);
        ImageReferenceProbe analysisReference = mock(ImageReferenceProbe.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC);
        Image referenced = image("referenced");
        Image unused = image("unused");
        when(repository.findCleanupCandidates(any(), any(), any(), any(), any()))
                .thenReturn(List.of(referenced, unused));
        when(analysisReference.exists("referenced")).thenReturn(true);
        when(analysisReference.exists("unused")).thenReturn(false);
        ImageCleanupService service = new ImageCleanupService(
                repository,
                storage,
                List.of(analysisReference),
                clock
        );

        List<String> claimedIds = service.claimExpiredImages();

        assertThat(claimedIds).containsExactly("unused");
        assertThat(referenced.getStatus()).isEqualTo(ImageStatus.PENDING_UPLOAD);
        assertThat(unused.getStatus()).isEqualTo(ImageStatus.DELETING);
    }

    @Test
    void continuesAfterReferencedFirstBatchToClaimLaterUnusedImage() {
        ImageRepository repository = mock(ImageRepository.class);
        ImageObjectStorage storage = mock(ImageObjectStorage.class);
        ImageReferenceProbe analysisReference = mock(ImageReferenceProbe.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC);
        List<Image> referencedImages = IntStream.range(0, 50)
                .mapToObj(index -> image("referenced-%02d".formatted(index)))
                .toList();
        Image unused = image("unused");
        when(repository.findCleanupCandidates(any(), any(), any(), any(), any()))
                .thenReturn(referencedImages, List.of(unused));
        referencedImages.forEach(image ->
                when(analysisReference.exists(image.getId())).thenReturn(true));
        ImageCleanupService service = new ImageCleanupService(
                repository,
                storage,
                List.of(analysisReference),
                clock
        );

        List<String> claimedIds = service.claimExpiredImages();

        assertThat(claimedIds).containsExactly("unused");
        assertThat(unused.getStatus()).isEqualTo(ImageStatus.DELETING);
        verify(repository, times(2)).findCleanupCandidates(
                any(), any(), any(), any(), any()
        );
    }

    @Test
    void claimsReleasedActiveImageOnlyWhenNoDomainReferenceRemains() {
        ImageRepository repository = mock(ImageRepository.class);
        ImageObjectStorage storage = mock(ImageObjectStorage.class);
        ImageReferenceProbe analysisReference = mock(ImageReferenceProbe.class);
        ImageReferenceProbe lookbookReference = mock(ImageReferenceProbe.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC);
        Image referenced = activeImage("referenced");
        Image released = activeImage("released");
        when(repository.findAllByIdInAndStatusForUpdate(
                List.of("referenced", "released"),
                ImageStatus.ACTIVE
        )).thenReturn(List.of(referenced, released));
        when(analysisReference.exists("referenced")).thenReturn(true);
        when(analysisReference.exists("released")).thenReturn(false);
        when(lookbookReference.exists("released")).thenReturn(false);
        ImageCleanupService service = new ImageCleanupService(
                repository,
                storage,
                List.of(analysisReference, lookbookReference),
                clock
        );

        List<String> claimedIds = service.claimReleasedActiveImages(
                List.of("referenced", "released")
        );

        assertThat(claimedIds).containsExactly("released");
        assertThat(referenced.getStatus()).isEqualTo(ImageStatus.ACTIVE);
        assertThat(released.getStatus()).isEqualTo(ImageStatus.DELETING);
    }

    private Image image(String id) {
        Member member = Member.create(
                id + "@example.com",
                "주녁" + id,
                "password",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(member, "id", 1L);
        return Image.createPending(
                id,
                member,
                "prod/images/analysis_original/2026/07/" + id + ".jpg",
                ImagePurpose.ANALYSIS,
                "image/jpeg",
                1024,
                ImageVisibility.PRIVATE,
                Instant.parse("2026-07-21T00:00:00Z")
        );
    }

    private Image activeImage(String id) {
        Image image = image(id);
        image.completeUpload(
                1024,
                "image/jpeg",
                java.time.LocalDateTime.of(2026, 7, 21, 1, 0)
        );
        image.activateForAnalysis(1L, Instant.parse("2026-07-21T01:00:00Z"));
        return image;
    }
}

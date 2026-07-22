package com.fitback.backend.domain.image.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
        when(repository.findCleanupCandidates(any(), any(), any(), any()))
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
        assertThat(referenced.getStatus()).isEqualTo(ImageStatus.PENDING);
        assertThat(unused.getStatus()).isEqualTo(ImageStatus.DELETING);
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
                ImagePurpose.ANALYSIS_ORIGINAL,
                "image/jpeg",
                1024,
                ImageVisibility.PRIVATE,
                Instant.parse("2026-07-21T00:00:00Z")
        );
    }
}

package com.fitback.backend.domain.image.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.image.entity.ImageAsset;
import com.fitback.backend.domain.image.entity.ImageAssetStatus;
import com.fitback.backend.domain.image.entity.ImagePurpose;
import com.fitback.backend.domain.image.repository.ImageAssetRepository;
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
        ImageAssetRepository repository = mock(ImageAssetRepository.class);
        ImageObjectStorage storage = mock(ImageObjectStorage.class);
        ImageReferenceProbe analysisReference = mock(ImageReferenceProbe.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC);
        ImageAsset referenced = image(10L);
        ImageAsset unused = image(11L);
        when(repository.findCleanupCandidates(any(), any(), any()))
                .thenReturn(List.of(referenced, unused));
        when(analysisReference.exists(10L)).thenReturn(true);
        when(analysisReference.exists(11L)).thenReturn(false);
        ImageCleanupService service = new ImageCleanupService(
                repository,
                storage,
                List.of(analysisReference),
                clock
        );

        List<Long> claimedIds = service.claimExpiredImages();

        assertThat(claimedIds).containsExactly(11L);
        assertThat(referenced.getAssetStatus()).isEqualTo(ImageAssetStatus.PENDING_UPLOAD);
        assertThat(unused.getAssetStatus()).isEqualTo(ImageAssetStatus.DELETING);
    }

    private ImageAsset image(Long id) {
        Member member = Member.create(
                "member" + id + "@example.com",
                "주녁" + id,
                "password",
                LoginProvider.EMAIL
        );
        ReflectionTestUtils.setField(member, "id", 1L);
        ImageAsset image = ImageAsset.create(
                member,
                ImagePurpose.ANALYSIS,
                "images/analysis/1/2026/07/" + id + ".jpg",
                "image/jpeg",
                1024,
                Instant.parse("2026-07-21T00:00:00Z")
        );
        ReflectionTestUtils.setField(image, "id", id);
        return image;
    }
}

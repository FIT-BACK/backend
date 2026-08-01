package com.fitback.backend.domain.image.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImagePurpose;
import com.fitback.backend.domain.image.entity.ImageStatus;
import com.fitback.backend.domain.image.entity.ImageVisibility;
import com.fitback.backend.domain.image.event.ImageReferencesReleasedEvent;
import com.fitback.backend.domain.image.repository.ImageRepository;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@SpringBootTest
class ImageReferenceReleaseTransactionIntegrationTest {

    private static final String IMAGE_ID = "released-active-image";
    private static final String OBJECT_KEY = "images/analysis/1/2026/08/released-active-image.jpg";
    private static final String MEMBER_EMAIL = "image-release-transaction@fitback.com";

    @Autowired
    private ImageRepository imageRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private ImageObjectStorage imageObjectStorage;

    @AfterEach
    void cleanUp() {
        transactionTemplate.executeWithoutResult(status -> {
            imageRepository.findById(IMAGE_ID).ifPresent(imageRepository::delete);
            memberRepository.findByEmail(MEMBER_EMAIL).ifPresent(memberRepository::delete);
        });
    }

    @Test
    void releasesActiveImageOnlyAfterPublishingTransactionCommits() {
        createActiveImage();
        AtomicReference<ImageStatus> statusBeforeCommit = new AtomicReference<>();

        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new ImageReferencesReleasedEvent(List.of(IMAGE_ID)));
            statusBeforeCommit.set(imageRepository.findById(IMAGE_ID).orElseThrow().getStatus());
        });

        assertThat(statusBeforeCommit.get()).isEqualTo(ImageStatus.ACTIVE);
        Image releasedImage = imageRepository.findById(IMAGE_ID).orElseThrow();
        assertThat(releasedImage.getStatus()).isEqualTo(ImageStatus.DELETED);
        assertThat(releasedImage.getDeletedAt()).isNotNull();
        verify(imageObjectStorage).delete(OBJECT_KEY);
    }

    private void createActiveImage() {
        transactionTemplate.executeWithoutResult(status -> {
            Member member = memberRepository.save(Member.create(
                    MEMBER_EMAIL,
                    "image-release-member",
                    "encoded-password",
                    LoginProvider.EMAIL
            ));
            Image image = Image.createPending(
                    IMAGE_ID,
                    member,
                    OBJECT_KEY,
                    ImagePurpose.ANALYSIS,
                    "image/jpeg",
                    1024,
                    ImageVisibility.PRIVATE,
                    Instant.parse("2026-08-02T00:05:00Z")
            );
            image.completeUpload(
                    1024,
                    "image/jpeg",
                    LocalDateTime.of(2026, 8, 2, 9, 0)
            );
            image.activateForAnalysis(
                    member.getId(),
                    Instant.parse("2026-08-02T00:00:00Z")
            );
            imageRepository.save(image);
        });
    }
}

package com.fitback.backend.domain.lookbook.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static com.fitback.backend.domain.lookbook.LookbookImageFixtures.readyImage;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImagePurpose;
import com.fitback.backend.domain.image.entity.ImageStatus;
import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.lookbook.entity.LookbookModerationStatus;
import com.fitback.backend.domain.lookbook.entity.LookbookTag;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository.RelatedLookbookRank;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.domain.trend.entity.TrendContent;
import com.fitback.backend.domain.trend.entity.TrendTag;
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
class LookbookRepositoryTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private LookbookRepository lookbookRepository;

    @Autowired
    private LookbookTagRepository lookbookTagRepository;

    @Autowired
    private LookbookImageRepository lookbookImageRepository;

    //공개 상세 조회에서는 신고로 숨김 처리된 룩북 제외
    @Test
    void findsOnlyVisibleLookbookForPublicDetail() {
        Member member = Member.create(
                "detail-owner@fitback.com",
                "detail-owner",
                "password",
                LoginProvider.EMAIL
        );
        entityManager.persist(member);
        Lookbook visible = persistLookbook(member, "detail-visible");
        Lookbook hidden = createLookbook(member, "detail-hidden");
        ReflectionTestUtils.setField(
                hidden,
                "moderationStatus",
                LookbookModerationStatus.AUTO_HIDDEN
        );
        entityManager.persist(hidden);
        entityManager.flush();
        entityManager.clear();

        assertThat(lookbookRepository.findByIdAndDeletedAtIsNullAndModerationStatus(
                visible.getId(),
                LookbookModerationStatus.VISIBLE
        )).isPresent();
        assertThat(lookbookRepository.findByIdAndDeletedAtIsNullAndModerationStatus(
                hidden.getId(),
                LookbookModerationStatus.VISIBLE
        )).isEmpty();
    }

    @Test
    void findsOwnedImagesAndActivatesOnlyReadyImages() {
        Member member = Member.create(
                "image-owner@fitback.com",
                "image-owner",
                "password",
                LoginProvider.EMAIL
        );
        entityManager.persist(member);
        Image originalImage = readyImage(
                "repository-original",
                member,
                ImagePurpose.LOOKBOOK
        );
        Image matchedImage = readyImage(
                "repository-matched",
                member,
                ImagePurpose.LOOKBOOK
        );
        entityManager.persist(originalImage);
        entityManager.persist(matchedImage);
        entityManager.flush();

        assertThat(lookbookImageRepository.findAllOwnedImages(
                List.of(originalImage.getId(), matchedImage.getId()),
                member.getId()
        )).extracting(Image::getId)
                .containsExactlyInAnyOrder(originalImage.getId(), matchedImage.getId());

        Instant activatedAt = Instant.parse("2026-07-23T10:00:00Z");
        int activatedCount = lookbookImageRepository.activateReadyImages(
                List.of(originalImage.getId(), matchedImage.getId()),
                ImageStatus.READY,
                ImageStatus.ACTIVE,
                activatedAt
        );
        entityManager.clear();

        assertThat(activatedCount).isEqualTo(2);
        Image activatedOriginal = entityManager.find(Image.class, originalImage.getId());
        assertThat(activatedOriginal.getStatus()).isEqualTo(ImageStatus.ACTIVE);
        assertThat(activatedOriginal.getActivatedAt()).isEqualTo(activatedAt);

        int reactivatedCount = lookbookImageRepository.activateReadyImages(
                List.of(originalImage.getId(), matchedImage.getId()),
                ImageStatus.READY,
                ImageStatus.ACTIVE,
                activatedAt.plusSeconds(60)
        );
        entityManager.clear();

        assertThat(reactivatedCount).isZero();
        assertThat(entityManager.find(Image.class, originalImage.getId()).getActivatedAt())
                .isEqualTo(activatedAt);
    }

    @Test
    void activeLookbookQueriesExcludeSoftDeletedLookbooks() {
        Member member = Member.create(
                "member@fitback.com",
                "fitback",
                "password",
                LoginProvider.EMAIL
        );
        entityManager.persist(member);

        Lookbook activeLookbook = createLookbook(member, "active");
        Lookbook deletedLookbook = createLookbook(member, "deleted");
        deletedLookbook.softDelete();
        entityManager.persist(activeLookbook);
        entityManager.persist(deletedLookbook);
        entityManager.flush();
        entityManager.clear();

        List<Lookbook> lookbooks = lookbookRepository
                .findAllByDeletedAtIsNullAndModerationStatusOrderByCreatedAtDescIdDesc(
                        LookbookModerationStatus.VISIBLE,
                        PageRequest.of(0, 21)
                );

        assertThat(lookbooks)
                .extracting(Lookbook::getId)
                .containsExactly(activeLookbook.getId());
        assertThat(lookbookRepository.findByIdAndDeletedAtIsNull(activeLookbook.getId()))
                .isPresent();
        assertThat(lookbookRepository.findByIdAndDeletedAtIsNull(deletedLookbook.getId()))
                .isEmpty();
        assertThat(lookbookRepository.findById(deletedLookbook.getId())).isPresent();
    }

    @Test
    void tagFilteredQueriesReturnOnlyActiveLookbooksWithMatchingTag() {
        Member member = Member.create(
                "tag-member@fitback.com",
                "tag-member",
                "password",
                LoginProvider.EMAIL
        );
        entityManager.persist(member);

        Tag minimalTag = Tag.create("미니멀", TagType.DETAIL);
        Tag streetTag = Tag.create("스트릿", TagType.DETAIL);
        entityManager.persist(minimalTag);
        entityManager.persist(streetTag);

        Lookbook minimalLookbook = createLookbook(member, "minimal");
        Lookbook streetLookbook = createLookbook(member, "street");
        Lookbook deletedMinimalLookbook = createLookbook(member, "deleted-minimal");
        deletedMinimalLookbook.softDelete();
        entityManager.persist(minimalLookbook);
        entityManager.persist(streetLookbook);
        entityManager.persist(deletedMinimalLookbook);
        entityManager.persist(LookbookTag.create(minimalLookbook, minimalTag));
        entityManager.persist(LookbookTag.create(streetLookbook, streetTag));
        entityManager.persist(LookbookTag.create(deletedMinimalLookbook, minimalTag));
        entityManager.flush();
        entityManager.clear();

        List<Lookbook> lookbooks = lookbookRepository.findAllByTagName(
                "미니멀",
                LookbookModerationStatus.VISIBLE,
                PageRequest.of(0, 21)
        );

        assertThat(lookbooks)
                .extracting(Lookbook::getId)
                .containsExactly(minimalLookbook.getId());
        assertThat(lookbookRepository.findCursorByIdAndTagName(
                minimalLookbook.getId(),
                "미니멀"
        ))
                .isPresent();
        assertThat(lookbookRepository.findCursorByIdAndTagName(
                streetLookbook.getId(),
                "미니멀"
        ))
                .isEmpty();
        assertThat(lookbookRepository.findCursorByIdAndTagName(
                deletedMinimalLookbook.getId(),
                "미니멀"
        )).isPresent();
    }

    @Test
    void relatedLookbookQueriesApplyWeightsAndExcludeUnavailableLookbooks() {
        Member member = Member.create(
                "related-member@fitback.com",
                "related-member",
                "password",
                LoginProvider.EMAIL
        );
        entityManager.persist(member);

        Tag minimalTag = Tag.create("관련-미니멀", TagType.DETAIL);
        Tag neutralTag = Tag.create("관련-뉴트럴", TagType.DETAIL);
        Tag streetTag = Tag.create("관련-스트릿", TagType.DETAIL);
        entityManager.persist(minimalTag);
        entityManager.persist(neutralTag);
        entityManager.persist(streetTag);

        TrendContent trend = TrendContent.create(
                "관련 룩북 테스트",
                "https://cdn.fitback.app/trends/related.jpg",
                "트렌드 관련 룩북 조회 테스트",
                member
        );
        entityManager.persist(trend);
        entityManager.persist(TrendTag.create(trend, minimalTag, 100));
        entityManager.persist(TrendTag.create(trend, neutralTag, 10));

        Lookbook highest = persistLookbook(member, "related-highest");
        entityManager.persist(LookbookTag.create(highest, minimalTag));
        entityManager.persist(LookbookTag.create(highest, neutralTag));
        Lookbook latestTie = persistLookbook(member, "related-latest-tie");
        entityManager.persist(LookbookTag.create(latestTie, minimalTag));
        Lookbook olderTie = persistLookbook(member, "related-older-tie");
        entityManager.persist(LookbookTag.create(olderTie, minimalTag));

        Lookbook hidden = createLookbook(member, "related-hidden");
        ReflectionTestUtils.setField(
                hidden,
                "moderationStatus",
                LookbookModerationStatus.AUTO_HIDDEN
        );
        entityManager.persist(hidden);
        entityManager.persist(LookbookTag.create(hidden, minimalTag));
        entityManager.persist(LookbookTag.create(hidden, neutralTag));

        Lookbook deleted = createLookbook(member, "related-deleted");
        deleted.softDelete();
        entityManager.persist(deleted);
        entityManager.persist(LookbookTag.create(deleted, minimalTag));
        entityManager.persist(LookbookTag.create(deleted, neutralTag));

        Lookbook unrelated = persistLookbook(member, "related-unrelated");
        entityManager.persist(LookbookTag.create(unrelated, streetTag));

        entityManager.flush();
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 7, 12, 0);
        updateCreatedAt(highest, createdAt);
        updateCreatedAt(latestTie, createdAt.minusMinutes(1));
        updateCreatedAt(olderTie, createdAt.minusMinutes(2));
        entityManager.clear();

        List<RelatedLookbookRank> firstPage = lookbookRepository.findRelatedLookbookRanks(
                trend.getId(),
                LookbookModerationStatus.VISIBLE,
                PageRequest.of(0, 4)
        );

        assertThat(firstPage)
                .extracting(RelatedLookbookRank::getLookbookId)
                .containsExactly(highest.getId(), latestTie.getId(), olderTie.getId());
        assertThat(firstPage)
                .extracting(RelatedLookbookRank::getRelevanceScore)
                .containsExactly(110L, 100L, 100L);

        // 이전 페이지 이후 상태가 바뀐 커서도 정렬 위치를 복원할 수 있어야 함
        assertThat(lookbookRepository.findRelatedLookbookRank(
                trend.getId(),
                hidden.getId()
        )).isPresent();
        assertThat(lookbookRepository.findRelatedLookbookRank(
                trend.getId(),
                deleted.getId()
        )).isPresent();

        List<Lookbook> visibleLookbooks = lookbookRepository
                .findAllByIdInAndDeletedAtIsNullAndModerationStatus(
                        List.of(highest.getId(), hidden.getId(), deleted.getId()),
                        LookbookModerationStatus.VISIBLE
                );
        assertThat(visibleLookbooks)
                .extracting(Lookbook::getId)
                .containsExactly(highest.getId());

        List<RelatedLookbookRank> nextPage = lookbookRepository.findNextRelatedLookbookRanks(
                trend.getId(),
                LookbookModerationStatus.VISIBLE,
                100L,
                createdAt.minusMinutes(1),
                latestTie.getId(),
                PageRequest.of(0, 4)
        );

        assertThat(nextPage)
                .extracting(RelatedLookbookRank::getLookbookId)
                .containsExactly(olderTie.getId());
    }

    @Test
    void incrementLikeCountUpdatesActiveLookbookAtomically() {
        Member member = Member.create(
                "like-member@fitback.com",
                "like-member",
                "password",
                LoginProvider.EMAIL
        );
        entityManager.persist(member);

        Lookbook lookbook = createLookbook(member, "like");
        entityManager.persist(lookbook);
        entityManager.flush();

        int updatedRows = lookbookRepository.incrementLikeCount(lookbook.getId());

        assertThat(updatedRows).isEqualTo(1);
        assertThat(lookbookRepository.findLikeCountByIdAndDeletedAtIsNull(lookbook.getId()))
                .contains(1);
    }

    @Test
    void incrementLikeCountDoesNotUpdateSoftDeletedLookbook() {
        Member member = Member.create(
                "deleted-like-member@fitback.com",
                "deleted-like-member",
                "password",
                LoginProvider.EMAIL
        );
        entityManager.persist(member);

        Lookbook lookbook = createLookbook(member, "deleted-like");
        lookbook.softDelete();
        entityManager.persist(lookbook);
        entityManager.flush();

        int updatedRows = lookbookRepository.incrementLikeCount(lookbook.getId());

        assertThat(updatedRows).isZero();
        assertThat(lookbookRepository.findLikeCountByIdAndDeletedAtIsNull(lookbook.getId()))
                .isEmpty();
    }

    @Test
    void decrementLikeCountUpdatesActiveLookbookAtomically() {
        Member member = Member.create(
                "unlike-member@fitback.com",
                "unlike-member",
                "password",
                LoginProvider.EMAIL
        );
        entityManager.persist(member);

        Lookbook lookbook = createLookbook(member, "unlike");
        entityManager.persist(lookbook);
        entityManager.flush();
        lookbookRepository.incrementLikeCount(lookbook.getId());

        int updatedRows = lookbookRepository.decrementLikeCount(lookbook.getId());

        assertThat(updatedRows).isEqualTo(1);
        assertThat(lookbookRepository.findLikeCountByIdAndDeletedAtIsNull(lookbook.getId()))
                .contains(0);
    }

    @Test
    void deleteAllByLookbookIdRemovesAllTagRelations() {
        Member member = Member.create(
                "update-member@fitback.com",
                "update-member",
                "password",
                LoginProvider.EMAIL
        );
        entityManager.persist(member);

        Tag minimalTag = Tag.create("수정-미니멀", TagType.DETAIL);
        Tag streetTag = Tag.create("수정-스트릿", TagType.DETAIL);
        entityManager.persist(minimalTag);
        entityManager.persist(streetTag);

        Lookbook lookbook = createLookbook(member, "update");
        entityManager.persist(lookbook);
        entityManager.persist(LookbookTag.create(lookbook, minimalTag));
        entityManager.persist(LookbookTag.create(lookbook, streetTag));
        entityManager.flush();
        entityManager.clear();

        int deletedCount = lookbookTagRepository.deleteAllByLookbookId(lookbook.getId());
        entityManager.flush();
        entityManager.clear();

        assertThat(deletedCount).isEqualTo(2);
        assertThat(lookbookTagRepository.findAllByLookbookIdOrderByIdAsc(lookbook.getId()))
                .isEmpty();
    }

    private Lookbook createLookbook(Member member, String imageName) {
        Image originalImage = readyImage(
                imageName + "-original",
                member,
                ImagePurpose.LOOKBOOK
        );
        Image matchedImage = readyImage(
                imageName + "-matched",
                member,
                ImagePurpose.LOOKBOOK
        );
        entityManager.persist(originalImage);
        entityManager.persist(matchedImage);
        return Lookbook.create(
                member,
                originalImage,
                matchedImage,
                null,
                null
        );
    }

    private Lookbook persistLookbook(Member member, String imageName) {
        Lookbook lookbook = createLookbook(member, imageName);
        entityManager.persist(lookbook);
        return lookbook;
    }

    // JPA가 관리하는 생성 시간을 고정하여 동점 룩북의 정렬 조건 검증
    private void updateCreatedAt(Lookbook lookbook, LocalDateTime createdAt) {
        entityManager.createNativeQuery("""
                        UPDATE lookbook
                        SET created_at = :createdAt
                        WHERE lookbook_id = :lookbookId
                        """)
                .setParameter("createdAt", createdAt)
                .setParameter("lookbookId", lookbook.getId())
                .executeUpdate();
    }
}

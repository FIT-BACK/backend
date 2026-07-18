package com.fitback.backend.domain.lookbook.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class LookbookRepositoryTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private LookbookRepository lookbookRepository;

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
                .findAllByDeletedAtIsNullOrderByCreatedAtDescIdDesc(PageRequest.of(0, 21));

        assertThat(lookbooks)
                .extracting(Lookbook::getId)
                .containsExactly(activeLookbook.getId());
        assertThat(lookbookRepository.findByIdAndDeletedAtIsNull(activeLookbook.getId()))
                .isPresent();
        assertThat(lookbookRepository.findByIdAndDeletedAtIsNull(deletedLookbook.getId()))
                .isEmpty();
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

    private Lookbook createLookbook(Member member, String imageName) {
        return Lookbook.create(
                member,
                "https://s3.example.com/" + imageName + "-original.jpg",
                "https://s3.example.com/" + imageName + "-matched.jpg",
                null,
                null
        );
    }
}

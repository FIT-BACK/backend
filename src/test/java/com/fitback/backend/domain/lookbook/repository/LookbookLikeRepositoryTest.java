package com.fitback.backend.domain.lookbook.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.lookbook.entity.LookbookLike;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class LookbookLikeRepositoryTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private LookbookLikeRepository lookbookLikeRepository;

    @Test
    void deleteByLookbookIdAndMemberIdHardDeletesLike() {
        Member member = Member.create(
                "member@fitback.com",
                "fitback",
                "password",
                LoginProvider.EMAIL
        );
        entityManager.persist(member);

        Lookbook lookbook = Lookbook.create(
                member,
                "https://s3.example.com/original.jpg",
                "https://s3.example.com/matched.jpg",
                null,
                null
        );
        entityManager.persist(lookbook);

        LookbookLike lookbookLike = LookbookLike.create(lookbook, member);
        entityManager.persist(lookbookLike);
        entityManager.flush();
        Long lookbookLikeId = lookbookLike.getId();

        int deletedRows = lookbookLikeRepository.deleteByLookbookIdAndMemberId(
                lookbook.getId(),
                member.getId()
        );

        assertThat(deletedRows).isEqualTo(1);
        assertThat(lookbookLikeRepository.findById(lookbookLikeId)).isEmpty();
    }
}

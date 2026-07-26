package com.fitback.backend.domain.member.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.entity.PasswordResetToken;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@DataJpaTest
class PasswordResetTokenRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Test
    void findByTokenHashForUpdateLoadsTokenAndMember() {
        Member member = em.persist(Member.create(
                "member@fitback.com",
                "member",
                "encodedPw",
                LoginProvider.EMAIL
        ));
        String tokenHash = "a".repeat(64);
        em.persist(PasswordResetToken.create(
                member,
                tokenHash,
                LocalDateTime.of(2026, 7, 26, 18, 0)
        ));
        em.flush();
        em.clear();

        PasswordResetToken token = passwordResetTokenRepository
                .findByTokenHashForUpdate(tokenHash)
                .orElseThrow();

        assertThat(token.getMemberId()).isEqualTo(member.getId());
        assertThat(Hibernate.isInitialized(token.getMember())).isTrue();
        assertThat(token.getMember().getEmail()).isEqualTo("member@fitback.com");
    }

    @Test
    void findByTokenHashForUpdateReturnsEmptyForUnknownHash() {
        assertThat(passwordResetTokenRepository.findByTokenHashForUpdate("b".repeat(64)))
                .isEmpty();
    }

    @Test
    void findByEmailForUpdateReturnsMember() {
        Member member = em.persist(Member.create(
                "locked@fitback.com",
                "locked_member",
                "encodedPw",
                LoginProvider.EMAIL
        ));
        em.flush();
        em.clear();

        Member lockedMember = memberRepository
                .findByEmailForUpdate("locked@fitback.com")
                .orElseThrow();

        assertThat(lockedMember.getId()).isEqualTo(member.getId());
        assertThat(em.getEntityManager().getLockMode(lockedMember))
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }
}

package com.fitback.backend.domain.trend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.entity.MemberTag;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.domain.trend.entity.TrendContent;
import com.fitback.backend.domain.trend.entity.TrendTag;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
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
class TrendContentRepositoryTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TrendContentRepository trendContentRepository;

    @Autowired
    private TrendTagRepository trendTagRepository;

    @Test
    void personalizedQueriesPrioritizeInterestMatchesAndPreserveCursorOrder() {
        Member interestedMember = persistMember(
                "interested@fitback.com",
                "interested-member"
        );
        Member noInterestMember = persistMember(
                "no-interest@fitback.com",
                "no-interest-member"
        );
        Tag minimalTag = persistTag("개인화-미니멀");
        Tag streetTag = persistTag("개인화-스트릿");
        entityManager.persist(MemberTag.create(interestedMember, minimalTag));

        TrendContent olderMatched = persistTrend("관심-이전", interestedMember);
        entityManager.persist(TrendTag.create(olderMatched, minimalTag));
        TrendContent latestMatched = persistTrend("관심-최신", interestedMember);
        entityManager.persist(TrendTag.create(latestMatched, minimalTag));
        TrendContent latestUnmatched = persistTrend("비관심-최신", interestedMember);
        entityManager.persist(TrendTag.create(latestUnmatched, streetTag));
        TrendContent olderUnmatched = persistTrend("비관심-이전", interestedMember);
        entityManager.persist(TrendTag.create(olderUnmatched, streetTag));

        entityManager.flush();
        LocalDateTime baseTime = LocalDateTime.of(2026, 8, 7, 12, 0);
        updateCreatedAt(olderMatched, baseTime.minusMinutes(2));
        updateCreatedAt(latestMatched, baseTime.minusMinutes(1));
        updateCreatedAt(latestUnmatched, baseTime);
        updateCreatedAt(olderUnmatched, baseTime.minusMinutes(3));
        entityManager.clear();

        List<TrendContent> personalized = trendContentRepository
                .findAllPrioritizingMemberTags(
                        interestedMember.getId(),
                        PageRequest.of(0, 10)
                );

        assertThat(personalized)
                .extracting(TrendContent::getId)
                .containsExactly(
                        latestMatched.getId(),
                        olderMatched.getId(),
                        latestUnmatched.getId(),
                        olderUnmatched.getId()
                );

        boolean cursorMatchesInterest = trendTagRepository.existsMemberInterestMatch(
                olderMatched.getId(),
                interestedMember.getId()
        );
        List<TrendContent> nextPage = trendContentRepository
                .findNextPagePrioritizingMemberTags(
                        interestedMember.getId(),
                        cursorMatchesInterest,
                        baseTime.minusMinutes(2),
                        olderMatched.getId(),
                        PageRequest.of(0, 10)
                );

        assertThat(cursorMatchesInterest).isTrue();
        assertThat(nextPage)
                .extracting(TrendContent::getId)
                .containsExactly(latestUnmatched.getId(), olderUnmatched.getId());

        // 비관심 그룹에 진입한 뒤에는 앞선 관심 그룹이 다음 페이지에 다시 노출되지 않아야 함
        boolean unmatchedCursorMatchesInterest = trendTagRepository.existsMemberInterestMatch(
                latestUnmatched.getId(),
                interestedMember.getId()
        );
        List<TrendContent> nextPageAfterUnmatchedCursor = trendContentRepository
                .findNextPagePrioritizingMemberTags(
                        interestedMember.getId(),
                        unmatchedCursorMatchesInterest,
                        baseTime,
                        latestUnmatched.getId(),
                        PageRequest.of(0, 10)
                );

        assertThat(unmatchedCursorMatchesInterest).isFalse();
        assertThat(nextPageAfterUnmatchedCursor)
                .extracting(TrendContent::getId)
                .containsExactly(olderUnmatched.getId());

        List<TrendContent> noInterest = trendContentRepository
                .findAllPrioritizingMemberTags(
                        noInterestMember.getId(),
                        PageRequest.of(0, 10)
                );

        assertThat(noInterest)
                .extracting(TrendContent::getId)
                .containsExactly(
                        latestUnmatched.getId(),
                        latestMatched.getId(),
                        olderMatched.getId(),
                        olderUnmatched.getId()
                );
    }

    private Member persistMember(String email, String nickname) {
        Member member = Member.create(email, nickname, "password", LoginProvider.EMAIL);
        entityManager.persist(member);
        return member;
    }

    private Tag persistTag(String tagName) {
        Tag tag = Tag.create(tagName, TagType.DETAIL);
        entityManager.persist(tag);
        return tag;
    }

    private TrendContent persistTrend(String title, Member member) {
        TrendContent trend = TrendContent.create(
                title,
                "https://cdn.fitback.app/trends/" + title + ".jpg",
                title + " 설명",
                member
        );
        entityManager.persist(trend);
        return trend;
    }

    // JPA가 관리하는 생성 시간을 고정하여 개인화 그룹 내부의 최신순 검증
    private void updateCreatedAt(TrendContent trend, LocalDateTime createdAt) {
        entityManager.createNativeQuery("""
                        UPDATE trend_content
                        SET created_at = :createdAt
                        WHERE trend_id = :trendId
                        """)
                .setParameter("createdAt", createdAt)
                .setParameter("trendId", trend.getId())
                .executeUpdate();
    }
}

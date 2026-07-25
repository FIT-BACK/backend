package com.fitback.backend.domain.member.repository;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.entity.MemberTag;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//auditing(@CreatedDate)은 메인 설정 클래스의 @EnableJpaAuditing이 슬라이스에도 적용되므로 별도 설정 불필요
@ActiveProfiles("test")
@DataJpaTest
class MemberTagRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private MemberTagRepository memberTagRepository;

    //테스트용 회원 저장
    private Member persistMember(String email, String nickname) {
        return em.persist(Member.create(email, nickname, "encodedPw", LoginProvider.EMAIL));
    }

    //테스트용 태그 저장
    private Tag persistTag(String name, TagType type) {
        return em.persist(Tag.create(name, type));
    }

    //fetch join으로 조회하면 tag가 프록시가 아니라 실제로 로드
    @Test
    void findByMemberIdFetchTagLoadsTagTest() {
        Member member = persistMember("owner@fitback.com", "owner");
        Tag tag1 = persistTag("미니멀", TagType.SILHOUETTE);
        Tag tag2 = persistTag("블랙", TagType.COLOR);
        em.persist(MemberTag.create(member, tag1));
        em.persist(MemberTag.create(member, tag2));
        //영속성 컨텍스트 비워 지연 로딩 여부를 정확히 확인
        em.flush();
        em.clear();

        List<MemberTag> result = memberTagRepository.findByMemberIdFetchTag(member.getId());

        assertThat(result).hasSize(2);
        //join fetch라 각 tag가 즉시 초기화되어 있어야 함
        assertThat(result).allSatisfy(mt ->
                assertThat(Hibernate.isInitialized(mt.getTag())).isTrue());
        assertThat(result)
                .extracting(mt -> mt.getTag().getTagName())
                .containsExactlyInAnyOrder("미니멀", "블랙");
    }

    //해당 회원의 태그만 조회, 다른 회원 태그는 제외
    @Test
    void findByMemberIdFetchTagOnlyOwnMemberTest() {
        Member memberA = persistMember("a@fitback.com", "memberA");
        Member memberB = persistMember("b@fitback.com", "memberB");
        Tag tagA = persistTag("A태그", TagType.SILHOUETTE);
        Tag tagB = persistTag("B태그", TagType.COLOR);
        em.persist(MemberTag.create(memberA, tagA));
        em.persist(MemberTag.create(memberB, tagB));
        em.flush();
        em.clear();

        List<MemberTag> result = memberTagRepository.findByMemberIdFetchTag(memberA.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMember().getId()).isEqualTo(memberA.getId());
        assertThat(result.get(0).getTag().getTagName()).isEqualTo("A태그");
    }

    //관심 태그가 없는 회원은 빈 목록
    @Test
    void findByMemberIdFetchTagEmptyTest() {
        Member member = persistMember("empty@fitback.com", "empty");
        em.flush();
        em.clear();

        List<MemberTag> result = memberTagRepository.findByMemberIdFetchTag(member.getId());

        assertThat(result).isEmpty();
    }
}

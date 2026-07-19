package com.fitback.backend.domain.member.repository;

import com.fitback.backend.domain.member.entity.MemberTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MemberTagRepository extends JpaRepository<MemberTag, Long> {
    void deleteByMemberId(Long memberId);

    //태그까지 fetch join으로 함께 로드 (N+1 방지)
    @Query("select mt from MemberTag mt join fetch mt.tag where mt.member.id = :memberId")
    List<MemberTag> findByMemberIdFetchTag(@Param("memberId") Long memberId);
}

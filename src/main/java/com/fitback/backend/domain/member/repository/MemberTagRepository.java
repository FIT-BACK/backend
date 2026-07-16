package com.fitback.backend.domain.member.repository;

import com.fitback.backend.domain.member.entity.MemberTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MemberTagRepository extends JpaRepository<MemberTag, Long> {
    void deleteByMemberId(Long memberId);
}

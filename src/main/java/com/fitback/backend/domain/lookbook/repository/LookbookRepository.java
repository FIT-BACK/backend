package com.fitback.backend.domain.lookbook.repository;

import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface LookbookRepository extends JpaRepository<Lookbook, Long> {
    long countByMemberId(Long memberId);

    //회원 탈퇴 시 해당 회원의 룩북을 탈퇴 회원 계정으로 익명화(재지정)
    @Modifying
    @Query("update Lookbook l set l.member = :withdrawnMember where l.member.id = :memberId")
    void reassignToWithdrawnMember(@Param("memberId") Long memberId,
                                   @Param("withdrawnMember") Member withdrawnMember);
}

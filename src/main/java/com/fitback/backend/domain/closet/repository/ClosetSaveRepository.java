package com.fitback.backend.domain.closet.repository;

import com.fitback.backend.domain.closet.entity.ClosetSave;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClosetSaveRepository extends JpaRepository<ClosetSave, Long> {
    long countByMemberId(Long memberId);
}

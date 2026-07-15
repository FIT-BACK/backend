package com.fitback.backend.domain.lookbook.repository;

import com.fitback.backend.domain.lookbook.entity.Lookbook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LookbookRepository extends JpaRepository<Lookbook, Long> {
    long countByMemberId(Long memberId);

}

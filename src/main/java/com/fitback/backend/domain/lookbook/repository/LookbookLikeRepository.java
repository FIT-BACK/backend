package com.fitback.backend.domain.lookbook.repository;

import com.fitback.backend.domain.lookbook.entity.LookbookLike;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LookbookLikeRepository extends JpaRepository<LookbookLike, Long> {

    boolean existsByLookbookIdAndMemberId(Long lookbookId, Long memberId);
}

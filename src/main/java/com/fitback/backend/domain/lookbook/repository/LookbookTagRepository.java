package com.fitback.backend.domain.lookbook.repository;

import com.fitback.backend.domain.lookbook.entity.LookbookTag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LookbookTagRepository extends JpaRepository<LookbookTag, Long> {
}

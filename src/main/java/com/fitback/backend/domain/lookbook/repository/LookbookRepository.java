package com.fitback.backend.domain.lookbook.repository;

import com.fitback.backend.domain.lookbook.entity.Lookbook;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LookbookRepository extends JpaRepository<Lookbook, Long> {
}

package com.fitback.backend.domain.tag.repository;

import com.fitback.backend.domain.tag.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, Long> {

}

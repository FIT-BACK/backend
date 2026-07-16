package com.fitback.backend.domain.tag.repository;

import com.fitback.backend.domain.tag.entity.Tag;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, Long> {

    List<Tag> findTop3ByOrderByIdAsc();
}

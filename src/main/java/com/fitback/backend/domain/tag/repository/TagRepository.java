package com.fitback.backend.domain.tag.repository;

import com.fitback.backend.domain.tag.entity.Tag;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TagRepository extends JpaRepository<Tag, Long> {

    List<Tag> findTop3ByOrderByIdAsc();
    @Query("""
            SELECT DISTINCT tag
            FROM Tag tag
            JOIN FETCH tag.targetClothing
            ORDER BY tag.id ASC
            """)
    List<Tag> findAllByOrderByIdAsc();
    List<Tag> findAllByTagNameIn(Collection<String> tagNames);
}

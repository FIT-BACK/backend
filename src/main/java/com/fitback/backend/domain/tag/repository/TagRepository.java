package com.fitback.backend.domain.tag.repository;

import com.fitback.backend.domain.tag.entity.Tag;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, Long> {

    List<Tag> findTop3ByOrderByIdAsc();
    List<Tag> findAllByOrderByIdAsc();
    List<Tag> findAllByTagNameIn(Collection<String> tagNames);
}

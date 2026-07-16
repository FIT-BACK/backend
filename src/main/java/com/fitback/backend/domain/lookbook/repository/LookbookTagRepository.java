package com.fitback.backend.domain.lookbook.repository;

import com.fitback.backend.domain.lookbook.entity.LookbookTag;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LookbookTagRepository extends JpaRepository<LookbookTag, Long> {

    @EntityGraph(attributePaths = "tag")
    List<LookbookTag> findAllByLookbookIdOrderByIdAsc(Long lookbookId);
}

package com.fitback.backend.domain.lookbook.repository;

import com.fitback.backend.domain.lookbook.entity.LookbookTag;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LookbookTagRepository extends JpaRepository<LookbookTag, Long> {

    @EntityGraph(attributePaths = "tag")
    List<LookbookTag> findAllByLookbookIdOrderByIdAsc(Long lookbookId);

    @EntityGraph(attributePaths = "tag")
    List<LookbookTag> findAllByLookbookIdInOrderByIdAsc(List<Long> lookbookIds);

    @Modifying(flushAutomatically = true)
    @Query("""
            DELETE FROM LookbookTag lookbookTag
            WHERE lookbookTag.lookbook.id = :lookbookId
            """)
    int deleteAllByLookbookId(@Param("lookbookId") Long lookbookId);
}

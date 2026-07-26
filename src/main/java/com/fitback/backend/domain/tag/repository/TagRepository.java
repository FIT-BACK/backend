package com.fitback.backend.domain.tag.repository;

import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, Long> {

    List<Tag> findTop3ByOrderByIdAsc();

    List<Tag> findAllByOrderByTagNameAscIdAsc(Pageable pageable);

    List<Tag> findByTagTypeOrderByTagNameAscIdAsc(TagType tagType, Pageable pageable);

    List<Tag> findByTagNameContainingIgnoreCaseOrderByTagNameAscIdAsc(
            String query,
            Pageable pageable
    );

    List<Tag> findByTagTypeAndTagNameContainingIgnoreCaseOrderByTagNameAscIdAsc(
            TagType tagType,
            String query,
            Pageable pageable
    );
}

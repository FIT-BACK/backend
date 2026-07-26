package com.fitback.backend.domain.closet.repository;

import com.fitback.backend.domain.closet.entity.SavedAnalysisItem;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedAnalysisItemRepository extends JpaRepository<SavedAnalysisItem, Long> {

    @EntityGraph(attributePaths = "product")
    List<SavedAnalysisItem> findByClosetSaveIdOrderByCategoryAsc(Long closetSaveId);

    void deleteAllByClosetSaveId(Long closetSaveId);
}

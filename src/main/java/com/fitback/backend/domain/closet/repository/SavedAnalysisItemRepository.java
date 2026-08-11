package com.fitback.backend.domain.closet.repository;

import com.fitback.backend.domain.closet.entity.SavedAnalysisItem;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedAnalysisItemRepository extends JpaRepository<SavedAnalysisItem, Long> {

    // 최초 저장 요청 순서를 보존해야 해서(카테고리 enum 선언 순서가 아니라) ID(=삽입 순서) 기준으로 정렬한다.
    @EntityGraph(attributePaths = "product")
    List<SavedAnalysisItem> findByClosetSaveIdOrderByIdAsc(Long closetSaveId);

    void deleteAllByClosetSaveId(Long closetSaveId);
}

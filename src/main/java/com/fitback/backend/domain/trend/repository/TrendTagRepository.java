package com.fitback.backend.domain.trend.repository;

import com.fitback.backend.domain.trend.entity.TrendTag;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrendTagRepository extends JpaRepository<TrendTag, Long> {

    @EntityGraph(attributePaths = "tag")
    List<TrendTag> findAllByTrendIdOrderByIdAsc(Long trendId);

    @EntityGraph(attributePaths = "tag")
    List<TrendTag> findAllByTrendIdInOrderByIdAsc(List<Long> trendIds);
}

package com.fitback.backend.domain.trend.repository;

import com.fitback.backend.domain.trend.entity.TrendTag;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrendTagRepository extends JpaRepository<TrendTag, Long> {

    @EntityGraph(attributePaths = "tag")
    List<TrendTag> findAllByTrendIdOrderByIdAsc(Long trendId);

    @EntityGraph(attributePaths = "tag")
    List<TrendTag> findAllByTrendIdInOrderByIdAsc(List<Long> trendIds);

    // 커서 트렌드와 회원 관심 태그가 하나 이상 일치하는지 확인
    @Query("""
            SELECT COUNT(trendTag) > 0
            FROM TrendTag trendTag
            WHERE trendTag.trend.id = :trendId
              AND EXISTS (
                  SELECT memberTag.id
                  FROM MemberTag memberTag
                  WHERE memberTag.member.id = :memberId
                    AND memberTag.tag = trendTag.tag
              )
            """)
    boolean existsMemberInterestMatch(
            @Param("trendId") Long trendId,
            @Param("memberId") Long memberId
    );
}

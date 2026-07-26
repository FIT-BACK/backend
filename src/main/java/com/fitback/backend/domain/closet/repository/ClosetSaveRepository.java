package com.fitback.backend.domain.closet.repository;

import com.fitback.backend.domain.closet.entity.ClosetSave;
import com.fitback.backend.domain.closet.entity.ClosetTargetType;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClosetSaveRepository extends JpaRepository<ClosetSave, Long> {

    long countByMemberId(Long memberId);

    Optional<ClosetSave> findByMemberIdAndTargetTypeAndTargetId(
            Long memberId,
            ClosetTargetType targetType,
            Long targetId
    );

    Slice<ClosetSave> findByMemberIdAndTargetTypeOrderByIdDesc(
            Long memberId,
            ClosetTargetType targetType,
            Pageable pageable
    );

    Slice<ClosetSave> findByMemberIdAndTargetTypeAndIdLessThanOrderByIdDesc(
            Long memberId,
            ClosetTargetType targetType,
            Long id,
            Pageable pageable
    );
}

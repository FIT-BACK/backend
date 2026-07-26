package com.fitback.backend.domain.tag.service;

import com.fitback.backend.domain.tag.dto.TagResponse;
import com.fitback.backend.domain.tag.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TagService {

    private final TagRepository tagRepository;

    // 태그 목록 조회
    @Transactional(readOnly = true)
    public TagResponse.TagList getTags() {
        return TagResponse.TagList.toTagList(
                tagRepository.findAllByOrderByIdAsc().stream()
                        .map(TagResponse.TagItem::toTagItem)
                        .toList()
        );
    }
}

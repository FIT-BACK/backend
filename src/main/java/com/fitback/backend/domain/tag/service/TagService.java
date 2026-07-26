package com.fitback.backend.domain.tag.service;

import com.fitback.backend.domain.tag.dto.TagListResponse;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.domain.tag.repository.TagRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TagService {

    private final TagRepository tagRepository;

    @Transactional(readOnly = true)
    public TagListResponse getTags(TagType tagType, String query, int limit) {
        String normalizedQuery = normalizeQuery(query);
        PageRequest pageRequest = PageRequest.of(0, limit);
        List<Tag> tags;
        if (tagType != null && normalizedQuery != null) {
            tags = tagRepository
                    .findByTagTypeAndTagNameContainingIgnoreCaseOrderByTagNameAscIdAsc(
                            tagType,
                            normalizedQuery,
                            pageRequest
                    );
        } else if (tagType != null) {
            tags = tagRepository.findByTagTypeOrderByTagNameAscIdAsc(tagType, pageRequest);
        } else if (normalizedQuery != null) {
            tags = tagRepository.findByTagNameContainingIgnoreCaseOrderByTagNameAscIdAsc(
                    normalizedQuery,
                    pageRequest
            );
        } else {
            tags = tagRepository.findAllByOrderByTagNameAscIdAsc(pageRequest);
        }
        return TagListResponse.from(tags);
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            return null;
        }
        String normalized = query.trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1).trim();
        }
        return normalized.isEmpty() ? null : normalized;
    }
}

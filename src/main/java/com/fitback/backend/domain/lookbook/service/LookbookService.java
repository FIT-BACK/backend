package com.fitback.backend.domain.lookbook.service;

import com.fitback.backend.domain.lookbook.dto.LookbookRequest;
import com.fitback.backend.domain.lookbook.dto.LookbookResponse;
import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.lookbook.entity.LookbookTag;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookTagRepository;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.mock.TagRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LookbookService {

    private final LookbookRepository lookbookRepository;
    private final LookbookTagRepository lookbookTagRepository;
    private final TagRepository tagRepository;

    // 룩북 업로드
    @Transactional
    public LookbookResponse.LookbookCreate createLookbook(
            Member member,
            LookbookRequest.LookbookCreate request
    ) {

        // tagId로 tag 객체 찾기
        List<Long> tagIds = request.tagIds().stream()
                .distinct()
                .toList();
        List<Tag> tags = tagRepository.findAllById(tagIds);

        // lookbook 객체 생성 전 tag 유효성 검사
        validateAllTagsExist(tagIds, tags);

        // lookbook 객체 생성 후 저장
        Lookbook lookbook = Lookbook.create(
                member,
                request.originalImageUrl(),
                request.matchedImageUrl(),
                request.purchaseUrl(),
                request.comment()
        );
        Lookbook savedLookbook = lookbookRepository.save(lookbook);

        // tagId로 lookbookTag 객체 생성 후 저장
        Map<Long, Tag> tagsById = tags.stream()
                .collect(Collectors.toMap(Tag::getId, Function.identity()));
        List<LookbookTag> lookbookTags = tagIds.stream()
                .map(tagId -> LookbookTag.create(savedLookbook, tagsById.get(tagId)))
                .toList();
        lookbookTagRepository.saveAll(lookbookTags);

        return LookbookResponse.LookbookCreate.toLookbookCreate(savedLookbook, tagIds);
    }

    // tag 유효성 검사
    private void validateAllTagsExist(List<Long> tagIds, List<Tag> tags) {
        Set<Long> existingTagIds = tags.stream()
                .map(Tag::getId)
                .collect(Collectors.toSet());
        List<Long> missingTagIds = tagIds.stream()
                .filter(tagId -> !existingTagIds.contains(tagId))
                .toList();

        if (!missingTagIds.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "존재하지 않는 태그입니다: " + missingTagIds
            );
        }
    }
}

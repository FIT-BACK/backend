package com.fitback.backend.domain.lookbook.service;

import com.fitback.backend.domain.lookbook.dto.LookbookRequest;
import com.fitback.backend.domain.lookbook.dto.LookbookResponse;
import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.lookbook.entity.LookbookTag;
import com.fitback.backend.domain.lookbook.repository.LookbookLikeRepository;
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
    private final LookbookLikeRepository lookbookLikeRepository;
    private final TagRepository tagRepository;

    // 룩북 업로드
    @Transactional
    public LookbookResponse.LookbookCreate createLookbook(
            Member member,
            LookbookRequest.LookbookCreate request
    ) {

        // tagId로 태그 객체 찾기
        List<Long> tagIds = request.tagIds().stream()
                .distinct()
                .toList();
        List<Tag> tags = tagRepository.findAllById(tagIds);

        // 룩북 객체 생성 전 태그 유효성 검사
        validateAllTagsExist(tagIds, tags);

        // 룩북 객체 생성 후 저장
        Lookbook lookbook = Lookbook.create(
                member,
                request.originalImageUrl(),
                request.matchedImageUrl(),
                request.purchaseUrl(),
                request.comment()
        );
        Lookbook savedLookbook = lookbookRepository.save(lookbook);

        // tagId로 룩북-태그 객체 생성 후 저장
        Map<Long, Tag> tagsById = tags.stream()
                .collect(Collectors.toMap(Tag::getId, Function.identity()));
        List<LookbookTag> lookbookTags = tagIds.stream()
                .map(tagId -> LookbookTag.create(savedLookbook, tagsById.get(tagId)))
                .toList();
        lookbookTagRepository.saveAll(lookbookTags);

        return LookbookResponse.LookbookCreate.toLookbookCreate(savedLookbook, tagIds);
    }

    // 룩북 상세 조회
    @Transactional(readOnly = true)
    public LookbookResponse.LookbookDetail getLookbookDetail(Long lookbookId, Member member) {

        // lookbookId 유효성 검사 및 조회
        Lookbook lookbook = lookbookRepository.findById(lookbookId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "룩북을 찾을 수 없습니다."
                ));

        // 룩북-태그 조회
        List<LookbookResponse.TagInfo> tags = lookbookTagRepository
                .findAllByLookbookIdOrderByIdAsc(lookbookId)
                .stream()
                .map(LookbookTag::getTag)
                .map(LookbookResponse.TagInfo::toTagInfo)
                .toList();

        // 로그인 상태면 해당 룩북에 좋아요 눌렀는지 여부 계산
        boolean likedByMe = member != null
                && lookbookLikeRepository.existsByLookbookIdAndMemberId(lookbookId, member.getId());

        return LookbookResponse.LookbookDetail.toLookbookDetail(lookbook, tags, likedByMe);
    }

    // 태그 유효성 검사
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

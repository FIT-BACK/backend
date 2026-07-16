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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LookbookService {

    private static final int LOOKBOOK_PAGE_SIZE = 20;
    private static final Pageable LOOKBOOK_PAGE_REQUEST = PageRequest.of(0, LOOKBOOK_PAGE_SIZE + 1);

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

    // 룩북 목록 조회
    @Transactional(readOnly = true)
    public LookbookResponse.LookbookList getLookbooks(Long cursor, Member member) {

        // cursor 기준 다음 페이지 분량의 룩북 목록 조회
        List<Lookbook> lookbookPage = findLookbookPage(cursor);

        // 다음 페이지 존재 여부 계산
        boolean hasNext = lookbookPage.size() > LOOKBOOK_PAGE_SIZE;

        // 실제 화면에 보여줄 룩북 계산
        List<Lookbook> lookbooks = lookbookPage.subList(
                0,
                Math.min(lookbookPage.size(), LOOKBOOK_PAGE_SIZE)
        );

        // lookbookId 추출
        List<Long> lookbookIds = lookbooks.stream()
                .map(Lookbook::getId)
                .toList();

        // lookbookId 로 태그 조회
        Map<Long, List<LookbookResponse.TagInfo>> tagsByLookbookId = findTagsByLookbookId(lookbookIds);

        // 현재 로그인 한 유저가 좋아요를 누른 룩북 조회
        Set<Long> likedLookbookIds = findLikedLookbookIds(lookbookIds, member);

        // responseDTO 로 변환
        List<LookbookResponse.LookbookItem> items = lookbooks.stream()
                .map(lookbook -> LookbookResponse.LookbookItem.toLookbookItem(
                        lookbook,
                        tagsByLookbookId.getOrDefault(lookbook.getId(), List.of()),
                        likedLookbookIds.contains(lookbook.getId())
                ))
                .toList();

        // 다음 cursor 계산
        Long nextCursor = hasNext && !lookbooks.isEmpty()
                ? lookbooks.get(lookbooks.size() - 1).getId()
                : null;

        return LookbookResponse.LookbookList.toLookbookList(items, nextCursor, hasNext);
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

    // cursor 기준 룩북 조회
    private List<Lookbook> findLookbookPage(Long cursor) {

        // 첫 요청일 때 목록 조회
        if (cursor == null) {
            return lookbookRepository.findAllByOrderByCreatedAtDescIdDesc(LOOKBOOK_PAGE_REQUEST);
        }

        // cursor 유효성 확인 후 목록 조회
        Lookbook cursorLookbook = lookbookRepository.findById(cursor)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "커서에 해당하는 룩북을 찾을 수 없습니다."
                ));

        return lookbookRepository.findNextPage(
                cursorLookbook.getCreatedAt(),
                cursorLookbook.getId(),
                LOOKBOOK_PAGE_REQUEST
        );
    }

    // 룩북 id 로 태그 조회
    private Map<Long, List<LookbookResponse.TagInfo>> findTagsByLookbookId(List<Long> lookbookIds) {

        if (lookbookIds.isEmpty()) {
            return Map.of();
        }

        return lookbookTagRepository.findAllByLookbookIdInOrderByIdAsc(lookbookIds)
                .stream()
                .collect(Collectors.groupingBy(
                        lookbookTag -> lookbookTag.getLookbook().getId(),
                        Collectors.mapping(
                                lookbookTag -> LookbookResponse.TagInfo.toTagInfo(lookbookTag.getTag()),
                                Collectors.toList()
                        )
                ));
    }

    // 현재 로그인 한 유저가 좋아요를 누른 룩북 조회
    private Set<Long> findLikedLookbookIds(List<Long> lookbookIds, Member member) {
        if (member == null || lookbookIds.isEmpty()) {
            return Set.of();
        }
        return lookbookLikeRepository.findLikedLookbookIds(member.getId(), lookbookIds);
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

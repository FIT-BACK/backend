package com.fitback.backend.domain.lookbook.service;

import com.fitback.backend.domain.lookbook.dto.LookbookRequest;
import com.fitback.backend.domain.lookbook.dto.LookbookResponse;
import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.lookbook.entity.LookbookTag;
import com.fitback.backend.domain.lookbook.repository.LookbookLikeRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookTagRepository;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.entity.MemberRole;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.mock.TagRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LookbookService {

    private static final int DEFAULT_LOOKBOOK_PAGE_SIZE = 20;

    private final LookbookRepository lookbookRepository;
    private final LookbookTagRepository lookbookTagRepository;
    private final LookbookLikeRepository lookbookLikeRepository;
    private final TagRepository tagRepository;
    private final LookbookLikeCommandService lookbookLikeCommandService;

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
    public LookbookResponse.LookbookList getLookbooks(
            Long cursor,
            Integer pageSize,
            Member member
    ) {

        int resolvedPageSize = pageSize == null ? DEFAULT_LOOKBOOK_PAGE_SIZE : pageSize;
        Pageable pageRequest = PageRequest.of(0, resolvedPageSize + 1);

        // cursor 기준 다음 페이지 분량의 룩북 목록 조회
        List<Lookbook> lookbookPage = findLookbookPage(cursor, pageRequest);

        // 다음 페이지 존재 여부 계산
        boolean hasNext = lookbookPage.size() > resolvedPageSize;

        // 실제 화면에 보여줄 룩북 계산
        List<Lookbook> lookbooks = lookbookPage.subList(
                0,
                Math.min(lookbookPage.size(), resolvedPageSize)
        );

        // lookbookId 추출
        List<Long> lookbookIds = lookbooks.stream()
                .map(Lookbook::getId)
                .toList();

        // lookbookId 로 태그 조회
        Map<Long, List<String>> tagNamesByLookbookId = findTagNamesByLookbookId(lookbookIds);

        // 현재 로그인 한 유저가 좋아요를 누른 룩북 조회
        Set<Long> likedLookbookIds = findLikedLookbookIds(lookbookIds, member);

        // responseDTO 로 변환
        List<LookbookResponse.LookbookItem> items = lookbooks.stream()
                .map(lookbook -> LookbookResponse.LookbookItem.toLookbookItem(
                        lookbook,
                        tagNamesByLookbookId.getOrDefault(lookbook.getId(), List.of()),
                        likedLookbookIds.contains(lookbook.getId())
                ))
                .toList();

        // 다음 cursor 계산
        Long nextCursor = hasNext && !lookbooks.isEmpty()
                ? lookbooks.get(lookbooks.size() - 1).getId()
                : null;

        return LookbookResponse.LookbookList.toLookbookList(
                items,
                nextCursor,
                hasNext,
                resolvedPageSize
        );
    }

    // 룩북 상세 조회
    @Transactional(readOnly = true)
    public LookbookResponse.LookbookDetail getLookbookDetail(Long lookbookId, Member member) {

        // lookbookId 유효성 검사 및 조회
        Lookbook lookbook = lookbookRepository.findByIdAndDeletedAtIsNull(lookbookId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "룩북을 찾을 수 없습니다."
                ));

        // 룩북-태그 조회
        List<String> tags = lookbookTagRepository
                .findAllByLookbookIdOrderByIdAsc(lookbookId)
                .stream()
                .map(LookbookTag::getTag)
                .map(Tag::getTagName)
                .toList();

        // 로그인 상태면 해당 룩북에 좋아요 눌렀는지 여부 계산
        boolean isLiked = member != null
                && lookbookLikeRepository.existsByLookbookIdAndMemberId(lookbookId, member.getId());

        return LookbookResponse.LookbookDetail.toLookbookDetail(lookbook, tags, isLiked);
    }

    // 룩북 삭제
    @Transactional
    public void deleteLookbook(Long lookbookId, Member member) {

        // lookbookId 유효성 검사 및 조회
        Lookbook lookbook = lookbookRepository.findByIdAndDeletedAtIsNull(lookbookId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "룩북을 찾을 수 없습니다."
                ));

        // ADMIN 이거나 작성자 본인이면 룩북 삭제
        boolean isOwner = Objects.equals(lookbook.getMember().getId(), member.getId());
        boolean isAdmin = member.getRole() == MemberRole.ADMIN;
        if (!isOwner && !isAdmin) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "룩북 삭제 권한이 없습니다.");
        }

        lookbook.softDelete();
    }

    // 룩북 좋아요
    public LookbookResponse.LookbookLike likeLookbook(Long lookbookId, Member member) {
        Integer likeCount;

        try {
            // 룩북-좋아요 엔티티 생성 시도
            likeCount = lookbookLikeCommandService.createLike(lookbookId, member);
        } catch (DataIntegrityViolationException exception) {
            // DB 유니크 제약 조건을 위반하면 catch

            // 실제로 이미 좋아요를 누른 것인지 확인
            boolean alreadyLiked = lookbookLikeRepository.existsByLookbookIdAndMemberId(
                    lookbookId,
                    member.getId()
            );

            // 다른 이유로 발생한 예외라면 다시 예외 발생
            if (!alreadyLiked) {
                throw exception;
            }

            // 이미 좋아요를 누른 게 확인 되었다면 현재 룩북의 좋아요를 반환 후 성공 처리
            likeCount = lookbookRepository.findLikeCountByIdAndDeletedAtIsNull(lookbookId)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.NOT_FOUND,
                            "룩북을 찾을 수 없습니다."
                    ));
        }

        return LookbookResponse.LookbookLike.toLookbookLike(likeCount);
    }

    // 룩북 좋아요 취소
    public LookbookResponse.LookbookLike deleteLookbookLike(Long lookbookId, Member member) {
        Integer likeCount = lookbookLikeCommandService.deleteLike(lookbookId, member);
        return LookbookResponse.LookbookLike.toLookbookLike(likeCount);
    }

    // cursor 기준 룩북 조회
    private List<Lookbook> findLookbookPage(Long cursor, Pageable pageRequest) {

        // 첫 요청일 때 목록 조회
        if (cursor == null) {
            return lookbookRepository.findAllByDeletedAtIsNullOrderByCreatedAtDescIdDesc(
                    pageRequest
            );
        }

        // cursor 유효성 확인 후 목록 조회
        Lookbook cursorLookbook = lookbookRepository.findByIdAndDeletedAtIsNull(cursor)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "커서에 해당하는 룩북을 찾을 수 없습니다."
                ));

        return lookbookRepository.findNextPage(
                cursorLookbook.getCreatedAt(),
                cursorLookbook.getId(),
                pageRequest
        );
    }

    // 룩북 id 로 태그명 조회
    private Map<Long, List<String>> findTagNamesByLookbookId(List<Long> lookbookIds) {

        if (lookbookIds.isEmpty()) {
            return Map.of();
        }

        return lookbookTagRepository.findAllByLookbookIdInOrderByIdAsc(lookbookIds)
                .stream()
                .collect(Collectors.groupingBy(
                        lookbookTag -> lookbookTag.getLookbook().getId(),
                        Collectors.mapping(
                                lookbookTag -> lookbookTag.getTag().getTagName(),
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

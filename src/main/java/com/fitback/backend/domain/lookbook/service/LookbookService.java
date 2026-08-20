package com.fitback.backend.domain.lookbook.service;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.analysis.service.AnalysisReportSaveService;
import com.fitback.backend.domain.closet.entity.ClosetSave;
import com.fitback.backend.domain.closet.entity.ClosetTargetType;
import com.fitback.backend.domain.closet.repository.ClosetSaveRepository;
import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImageStatus;
import com.fitback.backend.domain.image.event.ImageReferencesReleasedEvent;
import com.fitback.backend.domain.image.service.ImageAccessUrlProvider;
import com.fitback.backend.domain.lookbook.dto.LookbookRequest;
import com.fitback.backend.domain.lookbook.dto.LookbookResponse;
import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.lookbook.entity.LookbookModerationStatus;
import com.fitback.backend.domain.lookbook.entity.LookbookReport;
import com.fitback.backend.domain.lookbook.entity.LookbookTag;
import com.fitback.backend.domain.lookbook.repository.LookbookImageRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookLikeRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookReportRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository.RelatedLookbookRank;
import com.fitback.backend.domain.lookbook.repository.LookbookTagRepository;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.entity.MemberRole;
import com.fitback.backend.domain.member.service.MemberProfileImageService;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.repository.ProductRepository;
import com.fitback.backend.domain.recommendation.repository.RecommendedItemRepository;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.repository.TagRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LookbookService {

    private static final int DEFAULT_LOOKBOOK_PAGE_SIZE = 20;
    private static final int MAX_LOOKBOOK_PAGE_SIZE = 20;
    private static final int RELATED_LOOKBOOK_PAGE_SIZE = 3;
    private static final Pageable RELATED_LOOKBOOK_PAGE_REQUEST =
            PageRequest.of(0, RELATED_LOOKBOOK_PAGE_SIZE + 1);
    private static final Pageable SEARCH_PAGE_REQUEST = PageRequest.of(0, 10);

    private final LookbookRepository lookbookRepository;
    private final LookbookImageRepository lookbookImageRepository;
    private final LookbookTagRepository lookbookTagRepository;
    private final LookbookLikeRepository lookbookLikeRepository;
    private final ClosetSaveRepository closetSaveRepository;
    private final TagRepository tagRepository;
    private final LookbookLikeCommandService lookbookLikeCommandService;
    private final LookbookReportCommandService lookbookReportCommandService;
    private final LookbookReportRepository lookbookReportRepository;
    private final ImageAccessUrlProvider imageAccessUrlProvider;
    private final AnalysisReportRepository analysisReportRepository;
    private final AnalysisReportSaveService analysisReportSaveService;
    private final RecommendedItemRepository recommendedItemRepository;
    private final ProductRepository productRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MemberProfileImageService memberProfileImageService;
    private final LookbookProductImageResolver productImageResolver;
    private final LookbookTransactionExecutor transactionExecutor;

    // 룩북 업로드
    public LookbookResponse.LookbookCreate createLookbook(
            Member member,
            LookbookRequest.LookbookCreate request
    ) {
        String matchedProductImageUrl = resolveMatchedProductImage(member, request);
        return transactionExecutor.execute(() -> createLookbookInTransaction(
                member,
                request,
                matchedProductImageUrl
        ));
    }

    private LookbookResponse.LookbookCreate createLookbookInTransaction(
            Member member,
            LookbookRequest.LookbookCreate request,
            String matchedProductImageUrl
    ) {

        // tagId로 태그 객체 찾기
        List<Long> tagIds = request.tagIds();
        validateNoDuplicateTagIds(tagIds);
        List<Tag> tags = tagRepository.findAllById(tagIds);

        // 룩북 객체 생성 전 태그 유효성 검사
        validateAllTagsExist(tagIds, tags);
        LookbookAssets assets = resolveAssets(
                member,
                request.originalImageId(),
                request.matchedImageId(),
                request.matchedProductId(),
                request.sourceReportId()
        );

        // 룩북 객체 생성 후 저장
        Lookbook lookbook = createLookbook(
                member,
                request,
                assets,
                matchedProductImageUrl
        );
        Lookbook savedLookbook = lookbookRepository.save(lookbook);

        // tagId로 룩북-태그 객체 생성 후 저장
        Map<Long, Tag> tagsById = tags.stream()
                .collect(Collectors.toMap(Tag::getId, Function.identity()));
        List<LookbookTag> lookbookTags = tagIds.stream()
                .map(tagId -> LookbookTag.create(savedLookbook, tagsById.get(tagId)))
                .toList();
        lookbookTagRepository.saveAll(lookbookTags);
        activateReadyImages(assets);

        return LookbookResponse.LookbookCreate.toLookbookCreate(savedLookbook);
    }

    // 룩북 수정
    public LookbookResponse.LookbookUpdate updateLookbook(
            Long lookbookId,
            Member member,
            LookbookRequest.LookbookUpdate request
    ) {
        String matchedProductImageUrl = resolveMatchedProductImage(
                lookbookId,
                member,
                request
        );
        return transactionExecutor.execute(() -> updateLookbookInTransaction(
                lookbookId,
                member,
                request,
                matchedProductImageUrl
        ));
    }

    private LookbookResponse.LookbookUpdate updateLookbookInTransaction(
            Long lookbookId,
            Member member,
            LookbookRequest.LookbookUpdate request,
            String matchedProductImageUrl
    ) {

        // lookbookId 유효성 검사 및 조회
        Lookbook lookbook = findAuthorizedLookbook(lookbookId, member);

        // 룩북 내용 수정
        List<Long> tagIds = request.tagIds();
        validateNoDuplicateTagIds(tagIds);
        List<Tag> tags = tagRepository.findAllById(tagIds);
        validateAllTagsExist(tagIds, tags);
        LookbookAssets assets = resolveAssets(
                member,
                request.originalImageId(),
                request.matchedImageId(),
                request.matchedProductId(),
                request.sourceReportId()
        );

        List<String> releasedImageIds = imageIdsOf(lookbook);
        updateLookbook(lookbook, request, assets, matchedProductImageUrl);

        // 수정된 태그로 교체
        lookbookTagRepository.deleteAllByLookbookId(lookbookId);
        Map<Long, Tag> tagsById = tags.stream()
                .collect(Collectors.toMap(Tag::getId, Function.identity()));
        List<LookbookTag> lookbookTags = tagIds.stream()
                .map(tagId -> LookbookTag.create(lookbook, tagsById.get(tagId)))
                .toList();
        lookbookTagRepository.saveAll(lookbookTags);
        activateReadyImages(assets);
        publishReleasedImageReferences(releasedImageIds);

        return LookbookResponse.LookbookUpdate.toLookbookUpdate(lookbook);
    }

    // 룩북 신고
    public LookbookResponse.LookbookReport reportLookbook(
            Long lookbookId,
            Member member,
            LookbookRequest.LookbookReport request
    ) {
        LookbookReport report;

        try {
            // 룩북 신고 엔티티 생성 시도
            report = lookbookReportCommandService.createReport(
                    lookbookId,
                    member,
                    request.reason()
            );
        } catch (DataIntegrityViolationException exception) {
            // 해당 룩북을 이미 신고하였다면 생성하지 않음
            boolean alreadyReported = lookbookReportRepository.existsByLookbookIdAndMemberId(
                    lookbookId,
                    member.getId()
            );
            if (!alreadyReported) {
                // 해당 룩북을 신고하지 않은 상태인데 예외가 발생했다면 새로운 예외 처리
                throw exception;
            }
            throw new BusinessException(ErrorCode.BAD_REQUEST, "이미 신고한 룩북입니다.");
        }

        return LookbookResponse.LookbookReport.toLookbookReport(report);
    }

    // 룩북 목록 조회
    @Transactional(readOnly = true)
    public LookbookResponse.LookbookList getLookbooks(
            Long cursor,
            Integer pageSize,
            String tag,
            Member member
    ) {

        // pageSize 를 따로 입력받지 않으면 20 으로 계산
        int resolvedPageSize = pageSize == null ? DEFAULT_LOOKBOOK_PAGE_SIZE : pageSize;
        validateLookbookPageRequest(cursor, resolvedPageSize);

        // 입력 받은 태그의 앞 뒤 공백 제거
        String normalizedTag = normalizeTag(tag);
        Pageable pageRequest = PageRequest.of(0, resolvedPageSize + 1);

        // cursor 기준 다음 페이지 분량의 룩북 목록 조회
        List<Lookbook> lookbookPage = findLookbookPage(cursor, normalizedTag, pageRequest);

        // 다음 페이지 존재 여부 계산
        boolean hasNext = lookbookPage.size() > resolvedPageSize;

        // 실제 화면에 보여줄 룩북 계산
        List<Lookbook> lookbooks = lookbookPage.subList(
                0,
                Math.min(lookbookPage.size(), resolvedPageSize)
        );

        List<LookbookResponse.LookbookItem> items = toLookbookItems(lookbooks, member);

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

    // 마이클로젯 "내가 올린 룩북" 목록 조회 — 신고 숨김 상태와 무관하게 본인 목록엔 노출
    @Transactional(readOnly = true)
    public LookbookResponse.LookbookList getMyLookbooks(
            Long cursor,
            Integer pageSize,
            Member member
    ) {
        int resolvedPageSize = pageSize == null ? DEFAULT_LOOKBOOK_PAGE_SIZE : pageSize;
        validateLookbookPageRequest(cursor, resolvedPageSize);

        Pageable pageRequest = PageRequest.of(0, resolvedPageSize + 1);
        List<Lookbook> lookbookPage = findMyLookbookPage(cursor, member.getId(), pageRequest);

        boolean hasNext = lookbookPage.size() > resolvedPageSize;
        List<Lookbook> lookbooks = lookbookPage.subList(
                0,
                Math.min(lookbookPage.size(), resolvedPageSize)
        );

        List<LookbookResponse.LookbookItem> items = toLookbookItems(lookbooks, member);

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

    // 트렌드 태그 관련도 기준 룩북 목록 조회
    @Transactional(readOnly = true)
    public LookbookResponse.LookbookList getRelatedLookbooks(
            Long trendId,
            Long cursor,
            Member member
    ) {
        List<RelatedLookbookRank> rankedPage = findRelatedLookbookPage(trendId, cursor);

        // 한 개를 더 조회한 결과로 다음 페이지 존재 여부 계산
        boolean hasNext = rankedPage.size() > RELATED_LOOKBOOK_PAGE_SIZE;

        // 실제 화면에 반환할 룩북 3개 선택
        List<RelatedLookbookRank> displayedRanks = rankedPage.subList(
                0,
                Math.min(rankedPage.size(), RELATED_LOOKBOOK_PAGE_SIZE)
        );

        // 공통 태그가 없으면 추가 조회 없이 빈 목록 반환
        if (displayedRanks.isEmpty()) {
            return LookbookResponse.LookbookList.toLookbookList(
                    List.of(),
                    null,
                    false,
                    RELATED_LOOKBOOK_PAGE_SIZE
            );
        }

        // IN 조회 결과를 관련도 순서에 맞게 다시 정렬
        List<Long> lookbookIds = displayedRanks.stream()
                .map(RelatedLookbookRank::getLookbookId)
                .toList();
        Map<Long, Lookbook> lookbooksById = lookbookRepository
                .findAllByIdInAndDeletedAtIsNullAndModerationStatus(
                        lookbookIds,
                        LookbookModerationStatus.VISIBLE
                )
                .stream()
                .collect(Collectors.toMap(Lookbook::getId, Function.identity()));
        List<Lookbook> lookbooks = lookbookIds.stream()
                .map(lookbookId -> Optional.ofNullable(lookbooksById.get(lookbookId))
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)))
                .toList();

        List<LookbookResponse.LookbookItem> items = toLookbookItems(lookbooks, member);

        // 다음 페이지가 있으면 마지막 룩북 id를 다음 cursor로 반환
        Long nextCursor = hasNext && !displayedRanks.isEmpty()
                ? displayedRanks.get(displayedRanks.size() - 1).getLookbookId()
                : null;

        return LookbookResponse.LookbookList.toLookbookList(
                items,
                nextCursor,
                hasNext,
                RELATED_LOOKBOOK_PAGE_SIZE
        );
    }

    @Transactional(readOnly = true)
    public List<LookbookResponse.LookbookItem> searchLookbooks(
            String keyword,
            Member member
    ) {
        List<Lookbook> lookbooks = lookbookRepository.searchByKeyword(
                keyword,
                LookbookModerationStatus.VISIBLE,
                SEARCH_PAGE_REQUEST
        );
        return toLookbookItems(lookbooks, member);
    }

    // 룩북 상세 조회
    @Transactional(readOnly = true)
    public LookbookResponse.LookbookDetail getLookbookDetail(Long lookbookId, Member member) {

        //삭제되거나 신고로 숨김 처리된 룩북은 공개 상세 조회에서 제외
        Lookbook lookbook = lookbookRepository
                .findByIdAndDeletedAtIsNullAndModerationStatus(
                        lookbookId,
                        LookbookModerationStatus.VISIBLE
                )
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "룩북을 찾을 수 없습니다."
                ));

        // 룩북-태그 조회
        List<LookbookResponse.TagItem> tags = lookbookTagRepository
                .findAllByLookbookIdOrderByIdAsc(lookbookId)
                .stream()
                .map(LookbookTag::getTag)
                .map(LookbookResponse.TagItem::toTagItem)
                .toList();

        // 로그인 상태면 해당 룩북에 좋아요 눌렀는지 여부 계산
        boolean isLiked = member != null
                && lookbookLikeRepository.existsByLookbookIdAndMemberId(lookbookId, member.getId());

        // 로그인 회원이 룩북 작성자인지 여부 계산
        boolean isOwner = member != null
                && Objects.equals(lookbook.getMember().getId(), member.getId());
        String authorProfileImageUrl =
                memberProfileImageService.resolveProfileImageUrl(lookbook.getMember());

        // 로그인 상태면 해당 룩북을 마이 클로젯에 저장했는지 saveId로 계산
        Long saveId = member == null
                ? null
                : closetSaveRepository
                        .findByMemberIdAndTargetTypeAndTargetId(
                                member.getId(), ClosetTargetType.LOOKBOOK, lookbookId)
                        .map(ClosetSave::getId)
                        .orElse(null);

        return LookbookResponse.LookbookDetail.toLookbookDetail(
                lookbook,
                saveId,
                imageAccessUrlProvider.createReadUrl(lookbook.getOriginalImage()),
                resolveMatchedImageUrl(lookbook),
                authorProfileImageUrl,
                tags,
                isLiked,
                isOwner
        );
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

        List<String> releasedImageIds = imageIdsOf(lookbook);
        lookbook.softDelete();
        publishReleasedImageReferences(releasedImageIds);
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
    public LookbookResponse.LookbookUnlike deleteLookbookLike(Long lookbookId, Member member) {
        Integer likeCount = lookbookLikeCommandService.deleteLike(lookbookId, member);
        return LookbookResponse.LookbookUnlike.toLookbookUnlike(likeCount);
    }

    // 마이 클로젯 목록에 표시할 룩북 정보 조회
    @Transactional(readOnly = true)
    public Map<Long, ClosetLookbookView> findClosetViews(List<Long> lookbookIds) {

        if (lookbookIds.isEmpty()) {
            return Map.of();
        }

        // 저장 후 삭제된 룩북은 조회되지 않아 호출측 목록에서 빠짐
        List<Lookbook> lookbooks = lookbookRepository.findAllByIdInAndDeletedAtIsNull(lookbookIds);

        // 삭제 룩북의 태그는 남아있으므로 조회된 id 로만 태그 조회
        List<Long> foundIds = lookbooks.stream()
                .map(Lookbook::getId)
                .toList();
        Map<Long, List<String>> tagNamesByLookbookId = findTagNamesByLookbookId(foundIds);

        return lookbooks.stream()
                .collect(Collectors.toMap(
                        Lookbook::getId,
                        lookbook -> new ClosetLookbookView(
                                imageAccessUrlProvider.createReadUrl(lookbook.getOriginalImage()),
                                resolveMatchedImageUrl(lookbook),
                                tagNamesByLookbookId.getOrDefault(lookbook.getId(), List.of())
                        )
                ));
    }

    // cursor 기준 "내가 올린 룩북" 조회
    private List<Lookbook> findMyLookbookPage(
            Long cursor,
            Long memberId,
            Pageable pageRequest
    ) {
        if (cursor == null) {
            return lookbookRepository.findAllByMemberIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
                    memberId,
                    pageRequest
            );
        }

        // cursor 유효성 확인 후 cursor 에 해당하는 룩북 조회 (신고 숨김 여부와 무관하게
        // 본인 것이므로 findById로 충분 — findCursorLookbook과 달리 태그/노출상태 제약 없음)
        Lookbook cursorLookbook = lookbookRepository.findById(cursor)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "커서에 해당하는 룩북을 찾을 수 없습니다."
                ));

        return lookbookRepository.findNextPageByMemberId(
                memberId,
                cursorLookbook.getCreatedAt(),
                cursorLookbook.getId(),
                pageRequest
        );
    }

    // cursor 기준 룩북 조회
    private List<Lookbook> findLookbookPage(
            Long cursor,
            String tag,
            Pageable pageRequest
    ) {

        // 첫 요청일 때 목록 조회
        if (cursor == null) {
            if (tag != null) {
                // 선택된 태그가 있을 때
                return lookbookRepository.findAllByTagName(
                        tag,
                        LookbookModerationStatus.VISIBLE,
                        pageRequest
                );
            }
            // 선택된 태그가 없을 때
            return lookbookRepository
                    .findAllByDeletedAtIsNullAndModerationStatusOrderByCreatedAtDescIdDesc(
                            LookbookModerationStatus.VISIBLE,
                            pageRequest
                    );
        }

        // cursor 유효성 확인 후 cursor 에 해당하는 룩북 조회
        Lookbook cursorLookbook = findCursorLookbook(cursor, tag)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "커서에 해당하는 룩북을 찾을 수 없습니다."
                ));

        // 태그가 있을 때 목록 조회
        if (tag != null) {
            return lookbookRepository.findNextPageByTagName(
                    tag,
                    LookbookModerationStatus.VISIBLE,
                    cursorLookbook.getCreatedAt(),
                    cursorLookbook.getId(),
                    pageRequest
            );
        }

        // 태그가 없을 때 목록 조회
        return lookbookRepository.findNextPage(
                LookbookModerationStatus.VISIBLE,
                cursorLookbook.getCreatedAt(),
                cursorLookbook.getId(),
                pageRequest
        );
    }

    // 관련도 점수, 생성 시간, id 순서로 다음 룩북 페이지 조회
    private List<RelatedLookbookRank> findRelatedLookbookPage(Long trendId, Long cursor) {
        // 첫 페이지는 공통 태그 가중치 합계가 높은 룩북부터 조회
        if (cursor == null) {
            return lookbookRepository.findRelatedLookbookRanks(
                    trendId,
                    LookbookModerationStatus.VISIBLE,
                    RELATED_LOOKBOOK_PAGE_REQUEST
            );
        }

        // id만으로 정렬 위치를 알 수 없으므로 커서 룩북의 점수와 생성 시간을 함께 복원
        RelatedLookbookRank cursorRank = lookbookRepository.findRelatedLookbookRank(
                        trendId,
                        cursor
                )
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "커서에 해당하는 관련 룩북을 찾을 수 없습니다."
                ));
        return lookbookRepository.findNextRelatedLookbookRanks(
                trendId,
                LookbookModerationStatus.VISIBLE,
                cursorRank.getRelevanceScore(),
                cursorRank.getCreatedAt(),
                cursorRank.getLookbookId(),
                RELATED_LOOKBOOK_PAGE_REQUEST
        );
    }

    private Optional<Lookbook> findCursorLookbook(Long cursor, String tag) {
        if (tag != null) {
            return lookbookRepository.findCursorByIdAndTagName(cursor, tag);
        }
        return lookbookRepository.findById(cursor);
    }

    // 입력 받은 태그 공백 제거
    private String normalizeTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        return tag.trim();
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

    // 목록에서 상세로 안 들어가고도 저장/저장취소할 수 있게, 룩북별 closet-save id를 조회
    // (없으면 저장 안 한 상태 — 맵에 없는 키로 취급)
    private Map<Long, Long> findSaveIdsByLookbookId(List<Long> lookbookIds, Member member) {
        if (member == null || lookbookIds.isEmpty()) {
            return Map.of();
        }
        return closetSaveRepository
                .findAllByMemberIdAndTargetTypeAndTargetIdIn(
                        member.getId(),
                        ClosetTargetType.LOOKBOOK,
                        lookbookIds
                )
                .stream()
                .collect(Collectors.toMap(ClosetSave::getTargetId, ClosetSave::getId));
    }

    private List<LookbookResponse.LookbookItem> toLookbookItems(
            List<Lookbook> lookbooks,
            Member member
    ) {
        List<Long> lookbookIds = lookbooks.stream()
                .map(Lookbook::getId)
                .toList();
        Map<Long, List<String>> tagNamesByLookbookId = findTagNamesByLookbookId(
                lookbookIds
        );
        Set<Long> likedLookbookIds = findLikedLookbookIds(lookbookIds, member);
        Map<Long, Long> saveIdsByLookbookId = findSaveIdsByLookbookId(lookbookIds, member);
        Map<Long, String> profileImageUrls =
                memberProfileImageService.resolveProfileImageUrls(
                        lookbooks.stream()
                                .map(Lookbook::getMember)
                                .toList()
                );
        return lookbooks.stream()
                .map(lookbook -> LookbookResponse.LookbookItem.toLookbookItem(
                        lookbook,
                        imageAccessUrlProvider.createReadUrl(lookbook.getOriginalImage()),
                        resolveMatchedImageUrl(lookbook),
                        profileImageUrls.get(lookbook.getMember().getId()),
                        tagNamesByLookbookId.getOrDefault(lookbook.getId(), List.of()),
                        likedLookbookIds.contains(lookbook.getId()),
                        saveIdsByLookbookId.get(lookbook.getId())
                ))
                .toList();
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

    // 태그 ID 중복 검사
    private void validateNoDuplicateTagIds(List<Long> tagIds) {
        if (new HashSet<>(tagIds).size() != tagIds.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "태그 ID는 중복될 수 없습니다.");
        }
    }

    private String resolveMatchedProductImage(
            Member member,
            LookbookRequest.LookbookCreate request
    ) {
        if (request.matchedProductId() == null) {
            return null;
        }
        Product product = transactionExecutor.execute(() -> resolveAssets(
                member,
                request.originalImageId(),
                request.matchedImageId(),
                request.matchedProductId(),
                request.sourceReportId()
        ).matchedProduct());
        return productImageResolver.resolve(product);
    }

    private String resolveMatchedProductImage(
            Long lookbookId,
            Member member,
            LookbookRequest.LookbookUpdate request
    ) {
        if (request.matchedProductId() == null) {
            return null;
        }
        Product product = transactionExecutor.execute(() -> {
            findAuthorizedLookbook(lookbookId, member);
            return resolveAssets(
                    member,
                    request.originalImageId(),
                    request.matchedImageId(),
                    request.matchedProductId(),
                    request.sourceReportId()
            ).matchedProduct();
        });
        return productImageResolver.resolve(product);
    }

    private Lookbook findAuthorizedLookbook(Long lookbookId, Member member) {
        Lookbook lookbook = lookbookRepository.findByIdAndDeletedAtIsNull(lookbookId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "룩북을 찾을 수 없습니다."
                ));
        if (!Objects.equals(lookbook.getMember().getId(), member.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "룩북 수정 권한이 없습니다.");
        }
        return lookbook;
    }

    private LookbookAssets resolveAssets(
            Member member,
            String originalImageId,
            String matchedImageId,
            Long matchedProductId,
            Long sourceReportId
    ) {
        boolean hasMatchedImage = matchedImageId != null && !matchedImageId.isBlank();
        boolean hasMatchedProduct = matchedProductId != null;
        if (hasMatchedImage == hasMatchedProduct
                || (hasMatchedProduct && sourceReportId == null)
                || (!hasMatchedProduct && sourceReportId != null)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "매칭 이미지 또는 분석 추천 상품 중 하나만 선택해야 합니다."
            );
        }

        if (hasMatchedProduct) {
            Image originalImage = findAvailableOwnedOriginalImage(member, originalImageId);
            AnalysisReport sourceReport = findAuthorizedSourceReport(member, sourceReportId);
            validateSourceReportOriginalImage(sourceReport, originalImage);
            Product matchedProduct = findAuthorizedMatchedProduct(
                    member,
                    sourceReport,
                    sourceReportId,
                    matchedProductId
            );
            return new LookbookAssets(originalImage, null, matchedProduct);
        }

        List<String> imageIds = List.of(originalImageId, matchedImageId);
        Map<String, Image> imagesById = lookbookImageRepository
                .findAllOwnedImages(imageIds, member.getId())
                .stream()
                .collect(Collectors.toMap(Image::getId, Function.identity()));

        Image originalImage = findImage(imagesById, originalImageId);
        Image matchedImage = findImage(imagesById, matchedImageId);
        validateOriginalImage(originalImage);
        validateLookbookImage(matchedImage);
        return new LookbookAssets(originalImage, matchedImage, null);
    }

    private Image findAvailableOwnedOriginalImage(Member member, String originalImageId) {
        Map<String, Image> imagesById = lookbookImageRepository
                .findAllOwnedImages(List.of(originalImageId), member.getId())
                .stream()
                .collect(Collectors.toMap(Image::getId, Function.identity()));
        Image originalImage = findImage(imagesById, originalImageId);
        validateOriginalImage(originalImage);
        return originalImage;
    }

    private AnalysisReport findAuthorizedSourceReport(Member member, Long sourceReportId) {
        return analysisReportRepository
                .findByIdAndMemberIdAndDeletedAtIsNull(sourceReportId, member.getId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ANALYSIS_REPORT_NOT_FOUND
                ));
    }

    private void validateSourceReportOriginalImage(
            AnalysisReport sourceReport,
            Image originalImage
    ) {
        Image reportOriginalImage = sourceReport.getOriginalImage();
        if (reportOriginalImage == null
                || !Objects.equals(reportOriginalImage.getId(), originalImage.getId())) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "분석 리포트의 원본 이미지만 룩북에 사용할 수 있습니다."
            );
        }
    }

    private Product findAuthorizedMatchedProduct(
            Member member,
            AnalysisReport report,
            Long sourceReportId,
            Long matchedProductId
    ) {
        boolean currentResult = report.getRecommendationGeneratedAt() != null
                && report.hasRecommendationInputRevision(report.getResultInputRevision());
        boolean currentRecommendation = currentResult
                && recommendedItemRepository
                        .existsByReportIdAndProductId(sourceReportId, matchedProductId);
        boolean savedSelection = !currentRecommendation
                && analysisReportSaveService
                        .getState(member.getId(), sourceReportId)
                        .selectedItems()
                        .stream()
                        .anyMatch(item -> Objects.equals(item.productId(), matchedProductId));
        if (!currentRecommendation && !savedSelection) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "해당 분석 결과에서 선택할 수 없는 상품입니다."
            );
        }
        Product product = productRepository.findById(matchedProductId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return product;
    }

    private Image findImage(Map<String, Image> imagesById, String imageId) {
        Image image = imagesById.get(imageId);
        if (image == null) {
            throw new BusinessException(ErrorCode.IMAGE_NOT_FOUND);
        }
        return image;
    }

    private void validateLookbookImage(Image image) {
        boolean availableStatus = image.getStatus() == ImageStatus.READY
                || image.getStatus() == ImageStatus.ACTIVE;
        if (!image.getPurpose().isLookbook() || !availableStatus) {
            throw new BusinessException(ErrorCode.IMAGE_INVALID_STATE);
        }
    }

    private void validateOriginalImage(Image image) {
        boolean availableStatus = image.getStatus() == ImageStatus.READY
                || image.getStatus() == ImageStatus.ACTIVE;
        boolean allowedPurpose = image.getPurpose().isLookbook()
                || image.getPurpose().isAnalysis();
        if (!allowedPurpose || !availableStatus) {
            throw new BusinessException(ErrorCode.IMAGE_INVALID_STATE);
        }
    }

    private Lookbook createLookbook(
            Member member,
            LookbookRequest.LookbookCreate request,
            LookbookAssets assets,
            String matchedProductImageUrl
    ) {
        String purchaseUrl = resolvePurchaseUrl(request.purchaseUrl(), assets.matchedProduct());
        if (assets.matchedProduct() != null) {
            return Lookbook.createWithProduct(
                    member,
                    assets.originalImage(),
                    assets.matchedProduct(),
                    matchedProductImageUrl,
                    purchaseUrl,
                    request.comment()
            );
        }
        return Lookbook.create(
                member,
                assets.originalImage(),
                assets.matchedImage(),
                purchaseUrl,
                request.comment()
        );
    }

    private void updateLookbook(
            Lookbook lookbook,
            LookbookRequest.LookbookUpdate request,
            LookbookAssets assets,
            String matchedProductImageUrl
    ) {
        String purchaseUrl = resolvePurchaseUrl(request.purchaseUrl(), assets.matchedProduct());
        if (assets.matchedProduct() != null) {
            lookbook.updateWithProduct(
                    assets.originalImage(),
                    assets.matchedProduct(),
                    matchedProductImageUrl,
                    purchaseUrl,
                    request.comment()
            );
            return;
        }
        lookbook.update(
                assets.originalImage(),
                assets.matchedImage(),
                purchaseUrl,
                request.comment()
        );
    }

    private String resolvePurchaseUrl(String requestedUrl, Product matchedProduct) {
        return requestedUrl != null || matchedProduct == null
                ? requestedUrl
                : matchedProduct.getPurchaseUrl();
    }

    private String resolveMatchedImageUrl(Lookbook lookbook) {
        return lookbook.getMatchedProduct() == null
                ? imageAccessUrlProvider.createReadUrl(lookbook.getMatchedImage())
                : lookbook.getMatchedProductImageUrl();
    }

    private void activateReadyImages(LookbookAssets assets) {
        List<String> imageIds = assets.matchedImage() == null
                ? List.of(assets.originalImage().getId())
                : List.of(
                        assets.originalImage().getId(),
                        assets.matchedImage().getId()
                );
        lookbookImageRepository.activateReadyImages(
                imageIds,
                ImageStatus.READY,
                ImageStatus.ACTIVE,
                Instant.now()
        );
    }

    private List<String> imageIdsOf(Lookbook lookbook) {
        return java.util.stream.Stream.of(
                        lookbook.getOriginalImage(),
                        lookbook.getMatchedImage()
                )
                .filter(Objects::nonNull)
                .map(Image::getId)
                .distinct()
                .toList();
    }

    private void validateLookbookPageRequest(Long cursor, int pageSize) {
        if ((cursor != null && cursor <= 0)
                || pageSize < 1
                || pageSize > MAX_LOOKBOOK_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private void publishReleasedImageReferences(List<String> imageIds) {
        if (!imageIds.isEmpty()) {
            eventPublisher.publishEvent(new ImageReferencesReleasedEvent(imageIds));
        }
    }

    private record LookbookAssets(
            Image originalImage,
            Image matchedImage,
            Product matchedProduct
    ) {
    }

    // 마이 클로젯 목록 전달용, API 응답 DTO 아님
    public record ClosetLookbookView(
            String thumbnailUrl,
            String matchedImageUrl,
            List<String> tags
    ) {
    }
}

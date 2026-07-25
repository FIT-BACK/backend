package com.fitback.backend.domain.product.service;

import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.product.dto.ProductDetailResponse;
import com.fitback.backend.domain.product.dto.SavedProductListResponse;
import com.fitback.backend.domain.product.dto.SavedProductResponse;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.entity.SavedProduct;
import com.fitback.backend.domain.product.repository.ProductRepository;
import com.fitback.backend.domain.product.repository.SavedProductRepository;
import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductDataStatus;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SavedProductService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 20;

    private final SavedProductRepository savedProductRepository;
    private final MemberRepository memberRepository;
    private final ProductRepository productRepository;
    private final ProductDetailService productDetailService;
    private final SavedProductCursorCodec cursorCodec;

    public SavedProductService(
            SavedProductRepository savedProductRepository,
            MemberRepository memberRepository,
            ProductRepository productRepository,
            ProductDetailService productDetailService,
            SavedProductCursorCodec cursorCodec
    ) {
        this.savedProductRepository = savedProductRepository;
        this.memberRepository = memberRepository;
        this.productRepository = productRepository;
        this.productDetailService = productDetailService;
        this.cursorCodec = cursorCodec;
    }

    @Transactional
    public SaveResult save(Long memberId, Long productId) {
        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        return savedProductRepository
                .findByIdMemberIdAndIdProductId(memberId, productId)
                .map(saved -> new SaveResult(false, toSavedResponse(saved)))
                .orElseGet(() -> {
                    SavedProduct saved = savedProductRepository.saveAndFlush(
                            SavedProduct.create(member, product)
                    );
                    return new SaveResult(true, toSavedResponse(saved));
                });
    }

    public SavedProductListResponse findAll(
            Long memberId,
            String cursor,
            Integer requestedPageSize
    ) {
        int pageSize = validatePageSize(requestedPageSize);
        SavedProductCursorCodec.Cursor decodedCursor = cursorCodec.decode(cursor);
        PageRequest pageRequest = PageRequest.of(0, pageSize);
        Slice<SavedProduct> savedProducts = decodedCursor == null
                ? savedProductRepository.findFirstPage(memberId, pageRequest)
                : savedProductRepository.findNextPage(
                        memberId,
                        decodedCursor.createdAt(),
                        decodedCursor.productId(),
                        pageRequest
                );

        List<SavedProductListResponse.Item> items = new ArrayList<>();
        Set<String> warnings = new TreeSet<>();
        for (SavedProduct savedProduct : savedProducts.getContent()) {
            items.add(toListItem(savedProduct, warnings));
        }
        String nextCursor = savedProducts.hasNext() && !savedProducts.isEmpty()
                ? cursorCodec.encode(
                        savedProducts.getContent().getLast().getCreatedAt(),
                        savedProducts.getContent().getLast().getId().getProductId()
                )
                : null;
        return new SavedProductListResponse(
                items,
                nextCursor,
                savedProducts.hasNext(),
                pageSize,
                !warnings.isEmpty(),
                List.copyOf(warnings)
        );
    }

    @Transactional
    public SavedProductResponse delete(Long memberId, Long productId) {
        savedProductRepository.deleteSavedProduct(memberId, productId);
        return SavedProductResponse.unsaved(productId);
    }

    @Transactional(readOnly = true)
    public boolean isSaved(Long memberId, Long productId) {
        return savedProductRepository.existsByIdMemberIdAndIdProductId(memberId, productId);
    }

    private SavedProductListResponse.Item toListItem(
            SavedProduct savedProduct,
            Set<String> warnings
    ) {
        try {
            ProductDetailResponse detail = productDetailService.getDetail(
                    savedProduct.getId().getProductId()
            );
            return new SavedProductListResponse.Item(
                    detail.productId(),
                    detail.imageUrl(),
                    detail.name(),
                    detail.sellerName(),
                    detail.category(),
                    detail.price(),
                    detail.availability(),
                    detail.dataStatus(),
                    toInstant(savedProduct)
            );
        } catch (BusinessException exception) {
            warnings.add(exception.getErrorCode().getCode());
            return new SavedProductListResponse.Item(
                    savedProduct.getId().getProductId(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    ProductAvailability.TEMPORARILY_UNRESOLVED,
                    ProductDataStatus.STALE_SNAPSHOT,
                    toInstant(savedProduct)
            );
        }
    }

    private static SavedProductResponse toSavedResponse(SavedProduct savedProduct) {
        return SavedProductResponse.saved(
                savedProduct.getId().getProductId(),
                toInstant(savedProduct)
        );
    }

    private static Instant toInstant(SavedProduct savedProduct) {
        return savedProduct.getCreatedAt();
    }

    private static int validatePageSize(Integer requestedPageSize) {
        int pageSize = requestedPageSize == null ? DEFAULT_PAGE_SIZE : requestedPageSize;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "pageSize는 1 이상 20 이하여야 합니다."
            );
        }
        return pageSize;
    }

    public record SaveResult(
            boolean created,
            SavedProductResponse response
    ) {
    }
}

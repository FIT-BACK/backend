package com.fitback.backend.domain.product.controller;

import com.fitback.backend.domain.product.dto.ProductDetailResponse;
import com.fitback.backend.domain.product.dto.ProductSearchResponse;
import com.fitback.backend.domain.product.service.ProductDetailService;
import com.fitback.backend.domain.product.service.ProductSearchService;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.response.ApiResponse;
import com.fitback.backend.global.security.entity.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductSearchService productSearchService;
    private final ProductDetailService productDetailService;

    @Operation(
            summary = "상품 검색",
            description = "외부 상품 공급자를 검색하며 검색 자체는 Product를 저장하지 않습니다."
    )
    @GetMapping
    public ApiResponse<ProductSearchResponse> searchProducts(
            @RequestParam("keyword") String keyword,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @AuthenticationPrincipal AuthMember authMember
    ) {
        long memberId = requireMemberId(authMember);
        return ApiResponse.onSuccess(productSearchService.search(
                memberId,
                keyword,
                category,
                cursor,
                pageSize
        ));
    }

    @Operation(
            summary = "상품 상세",
            description = "materialize된 내부 상품을 공급자 live lookup 또는 허용 snapshot으로 조회합니다."
    )
    @GetMapping("/{productId}")
    public ApiResponse<ProductDetailResponse> getProductDetail(
            @PathVariable("productId") Long productId,
            @AuthenticationPrincipal AuthMember authMember
    ) {
        requireMemberId(authMember);
        return ApiResponse.onSuccess(productDetailService.getDetail(productId));
    }

    private static long requireMemberId(AuthMember authMember) {
        if (authMember == null || authMember.getMember().getId() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return authMember.getMember().getId();
    }
}

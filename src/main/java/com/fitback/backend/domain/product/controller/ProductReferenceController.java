package com.fitback.backend.domain.product.controller;

import com.fitback.backend.domain.product.dto.ProductReferenceCreateRequest;
import com.fitback.backend.domain.product.dto.ProductReferenceResponse;
import com.fitback.backend.domain.product.service.ProductMaterializationService;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import com.fitback.backend.global.response.ApiResponse;
import com.fitback.backend.global.security.entity.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/product-references")
public class ProductReferenceController {

    private final ProductMaterializationService productMaterializationService;

    @Operation(
            summary = "외부 상품 후보 materialize",
            description = "검색에서 발급한 candidateToken을 검증해 상세 조회 가능한 Product로 전환합니다."
    )
    @PostMapping
    public ResponseEntity<ApiResponse<ProductReferenceResponse>> materializeProduct(
            @Valid @RequestBody ProductReferenceCreateRequest request,
            @AuthenticationPrincipal AuthMember authMember
    ) {
        if (authMember == null || authMember.getMember().getId() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        ProductReferenceResponse response = productMaterializationService.materialize(
                authMember.getMember().getId(),
                request.candidateToken()
        );
        HttpStatus status = response.created() ? HttpStatus.CREATED : HttpStatus.OK;
        ApiResponse<ProductReferenceResponse> body = response.created()
                ? ApiResponse.onCreated(response)
                : ApiResponse.onSuccess(response);
        return ResponseEntity.status(status).body(body);
    }
}

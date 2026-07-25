package com.fitback.backend.domain.product.controller;

import com.fitback.backend.domain.product.dto.SavedProductListResponse;
import com.fitback.backend.domain.product.dto.SavedProductResponse;
import com.fitback.backend.domain.product.service.SavedProductService;
import com.fitback.backend.domain.product.service.SavedProductService.SaveResult;
import com.fitback.backend.global.response.ApiResponse;
import com.fitback.backend.global.security.CurrentMemberProvider;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members/me/saved-products")
public class SavedProductController {

    private final SavedProductService savedProductService;
    private final CurrentMemberProvider currentMemberProvider;

    @Operation(summary = "추천 상품 저장", description = "materialize된 상품을 멱등하게 저장합니다.")
    @PutMapping("/{productId}")
    public ResponseEntity<ApiResponse<SavedProductResponse>> save(
            @PathVariable @Positive Long productId
    ) {
        SaveResult result = savedProductService.save(
                currentMemberProvider.getCurrentMemberId(),
                productId
        );
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        ApiResponse<SavedProductResponse> body = result.created()
                ? ApiResponse.onCreated(result.response())
                : ApiResponse.onSuccess(result.response());
        return ResponseEntity.status(status).body(body);
    }

    @Operation(summary = "저장 상품 목록", description = "최신 저장순 커서 페이지로 조회합니다.")
    @GetMapping
    public ApiResponse<SavedProductListResponse> findAll(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "10") Integer pageSize
    ) {
        return ApiResponse.onSuccess(savedProductService.findAll(
                currentMemberProvider.getCurrentMemberId(),
                cursor,
                pageSize
        ));
    }

    @Operation(summary = "저장 상품 해제", description = "저장 관계가 없어도 성공을 반환합니다.")
    @DeleteMapping("/{productId}")
    public ApiResponse<SavedProductResponse> delete(
            @PathVariable @Positive Long productId
    ) {
        return ApiResponse.onSuccess(savedProductService.delete(
                currentMemberProvider.getCurrentMemberId(),
                productId
        ));
    }
}

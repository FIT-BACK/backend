package com.fitback.backend.domain.image.controller;

import com.fitback.backend.domain.image.dto.ImageUploadRequest;
import com.fitback.backend.domain.image.dto.ImageUploadResponse;
import com.fitback.backend.domain.image.service.ImageUploadService;
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
@RequestMapping("/api/v1/images")
public class ImageController {

    private final ImageUploadService imageUploadService;

    @Operation(
            summary = "이미지 업로드 URL 발급",
            description = "인증 회원의 private S3 직접 업로드를 위한 5분 유효 Presigned PUT URL을 발급합니다."
    )
    @PostMapping("/presigned-uploads")
    public ResponseEntity<ApiResponse<ImageUploadResponse>> createPresignedUpload(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody ImageUploadRequest request
    ) {
        ImageUploadResponse response = imageUploadService.createUpload(
                authMember.getMember(),
                request
        );
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.onCreated(response));
    }
}

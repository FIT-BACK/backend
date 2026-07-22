package com.fitback.backend.domain.image.controller;

import com.fitback.backend.domain.image.dto.ImageCompleteResponse;
import com.fitback.backend.domain.image.dto.ImageUploadRequest;
import com.fitback.backend.domain.image.dto.ImageUploadResponse;
import com.fitback.backend.domain.image.service.ImageAssetService;
import com.fitback.backend.global.response.ApiResponse;
import com.fitback.backend.global.security.CurrentMemberProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/images")
public class ImageController {

    private final ImageAssetService imageAssetService;
    private final CurrentMemberProvider currentMemberProvider;

    @PostMapping("/upload-requests")
    public ResponseEntity<ApiResponse<ImageUploadResponse>> issueUploadRequest(
            @Valid @RequestBody ImageUploadRequest request
    ) {
        ImageUploadResponse response = imageAssetService.issueUploadRequest(
                currentMemberProvider.getCurrentMemberId(),
                request
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.onCreated(response));
    }

    @PostMapping("/{imageId}/complete")
    public ApiResponse<ImageCompleteResponse> completeUpload(@PathVariable String imageId) {
        return ApiResponse.onSuccess(imageAssetService.completeUpload(
                currentMemberProvider.getCurrentMemberId(),
                imageId
        ));
    }

    @PostMapping("/{imageId}/upload-request")
    public ApiResponse<ImageUploadResponse> reissueUploadRequest(@PathVariable String imageId) {
        return ApiResponse.onSuccess(imageAssetService.reissueUploadRequest(
                currentMemberProvider.getCurrentMemberId(),
                imageId
        ));
    }
}

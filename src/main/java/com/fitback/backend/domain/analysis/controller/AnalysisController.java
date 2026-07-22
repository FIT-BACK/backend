package com.fitback.backend.domain.analysis.controller;

import com.fitback.backend.domain.analysis.dto.AnalysisByImageRequest;
import com.fitback.backend.domain.analysis.dto.AnalysisCreateResponse;
import com.fitback.backend.domain.analysis.dto.AnalysisDetailResponse;
import com.fitback.backend.domain.analysis.dto.AnalysisListResponse;
import com.fitback.backend.domain.analysis.dto.ConfirmTagsRequest;
import com.fitback.backend.domain.analysis.service.AnalysisService;
import com.fitback.backend.global.response.ApiResponse;
import com.fitback.backend.global.security.CurrentMemberProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/analyses")
public class AnalysisController {

    private final AnalysisService analysisService;
    private final CurrentMemberProvider currentMemberProvider;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<AnalysisCreateResponse>> createAnalysis(
            @RequestPart("image") MultipartFile image
    ) {
        AnalysisCreateResponse response = analysisService.create(
                currentMemberProvider.getCurrentMemberId(),
                image
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.onCreated(response));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<AnalysisCreateResponse>> createAnalysis(
            @Valid @RequestBody AnalysisByImageRequest request
    ) {
        AnalysisCreateResponse response = analysisService.create(
                currentMemberProvider.getCurrentMemberId(),
                request
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.onCreated(response));
    }

    @GetMapping
    public ApiResponse<AnalysisListResponse> getReports(
            @RequestParam(required = false) @Positive Long cursor,
            @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(50) Integer pageSize
    ) {
        return ApiResponse.onSuccess(analysisService.getReports(
                currentMemberProvider.getCurrentMemberId(),
                cursor,
                pageSize
        ));
    }

    @GetMapping("/{reportId}")
    public ApiResponse<AnalysisDetailResponse> getReport(
            @PathVariable @Positive Long reportId
    ) {
        return ApiResponse.onSuccess(analysisService.getReport(
                currentMemberProvider.getCurrentMemberId(),
                reportId
        ));
    }

    @PatchMapping("/{reportId}/recommendations")
    public ApiResponse<AnalysisDetailResponse> confirmTags(
            @PathVariable @Positive Long reportId,
            @Valid @RequestBody ConfirmTagsRequest request
    ) {
        return ApiResponse.onSuccess(analysisService.confirmTags(
                currentMemberProvider.getCurrentMemberId(),
                reportId,
                request
        ));
    }

    @DeleteMapping("/{reportId}")
    public ApiResponse<Void> deleteReport(@PathVariable @Positive Long reportId) {
        analysisService.deleteReport(currentMemberProvider.getCurrentMemberId(), reportId);
        return ApiResponse.onSuccess();
    }
}

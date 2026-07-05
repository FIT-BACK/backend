package com.fitback.backend.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void onSuccessCreatesSuccessResponse() {
        ApiResponse<String> response = ApiResponse.onSuccess("ok");

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("COMMON200_1");
        assertThat(response.message()).isEqualTo("성공적으로 요청을 처리했습니다.");
        assertThat(response.data()).isEqualTo("ok");
    }

    @Test
    void onCreatedCreatesCreatedResponse() {
        ApiResponse<String> response = ApiResponse.onCreated("created");

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("COMMON201_1");
        assertThat(response.message()).isEqualTo("리소스가 생성되었습니다.");
        assertThat(response.data()).isEqualTo("created");
    }

    @Test
    void onFailureCreatesFailureResponse() {
        ApiResponse<Void> response = ApiResponse.onFailure("COMMON400_1", "잘못된 요청입니다.", null);

        assertThat(response.success()).isFalse();
        assertThat(response.code()).isEqualTo("COMMON400_1");
        assertThat(response.message()).isEqualTo("잘못된 요청입니다.");
        assertThat(response.data()).isNull();
    }
}

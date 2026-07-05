package com.fitback.backend.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void onSuccessCreatesSuccessResponse() {
        ApiResponse<String> response = ApiResponse.onSuccess("ok");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.code()).isEqualTo("COMMON_200");
        assertThat(response.message()).isEqualTo("요청에 성공했습니다.");
        assertThat(response.result()).isEqualTo("ok");
    }

    @Test
    void onFailureCreatesFailureResponse() {
        ApiResponse<Void> response = ApiResponse.onFailure("COMMON_400", "잘못된 요청입니다.", null);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.code()).isEqualTo("COMMON_400");
        assertThat(response.message()).isEqualTo("잘못된 요청입니다.");
        assertThat(response.result()).isNull();
    }
}

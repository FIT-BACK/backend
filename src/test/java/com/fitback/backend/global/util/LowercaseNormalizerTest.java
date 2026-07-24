package com.fitback.backend.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LowercaseNormalizerTest {

    //앞뒤 공백 제거 후 소문자로 변환
    @Test
    void normalizeTrimAndLowercaseTest() {
        String normalized = LowercaseNormalizer.normalize("  Test@FITBACK.COM  ");

        assertThat(normalized).isEqualTo("test@fitback.com");
    }
}

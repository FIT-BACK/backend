package com.fitback.backend.global.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HmacUtilTest {

    private final HmacUtil hmacUtil = new HmacUtil("test-hmac-secret-key");

    @Test
    void hashHexCreatesLowercaseSha256Hex() {
        String hash = hmacUtil.hashHex("login-attempt:test@fitback.com");

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    // DB에 저장할 HMAC은 모든 사용 도메인에서 동일한 소문자 64자리 형식만 허용
    @Test
    void validateHashHexRejectsInvalidFormats() {
        assertThat(HmacUtil.validateHashHex("a".repeat(64)))
                .isEqualTo("a".repeat(64));

        assertThatThrownBy(() -> HmacUtil.validateHashHex(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> HmacUtil.validateHashHex("a".repeat(63)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HmacUtil.validateHashHex("A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HmacUtil.validateHashHex("z".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

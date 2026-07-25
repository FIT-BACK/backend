package com.fitback.backend.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SavedProductCursorCodecTest {

    private final SavedProductCursorCodec cursorCodec = new SavedProductCursorCodec();

    @Test
    void roundTripsCreatedAtAndProductId() {
        Instant createdAt = Instant.parse("2026-07-26T03:30:15.123456Z");

        SavedProductCursorCodec.Cursor cursor = cursorCodec.decode(
                cursorCodec.encode(createdAt, 101L)
        );

        assertThat(cursor.createdAt()).isEqualTo(createdAt);
        assertThat(cursor.productId()).isEqualTo(101L);
    }

    @Test
    void rejectsMalformedOrOversizedCursor() {
        assertInvalid("invalid");
        assertInvalid(" ".repeat(10));
        assertInvalid("x".repeat(257));
        assertInvalid(encodePayload("not-a-timestamp|1"));
        assertInvalid(encodePayload("2026-07-26T03:30:15.123456Z|0"));
        assertInvalid(encodePayload("2026-07-26T03:30:15.123456Z|not-a-number"));
        assertInvalid(encodePayload("2026-07-26T03:30:15.123456Z|1|extra"));
    }

    private static String encodePayload(String payload) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private void assertInvalid(String cursor) {
        assertThatThrownBy(() -> cursorCodec.decode(cursor))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}

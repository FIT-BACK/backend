package com.fitback.backend.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.time.Instant;
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
    }

    private void assertInvalid(String cursor) {
        assertThatThrownBy(() -> cursorCodec.decode(cursor))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}

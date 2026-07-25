package com.fitback.backend.domain.product.service;

import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
class SavedProductCursorCodec {

    private static final int MAX_CURSOR_LENGTH = 256;

    String encode(Instant createdAt, Long productId) {
        String payload = createdAt + "|" + productId;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    Cursor decode(String cursor) {
        if (cursor == null) {
            return null;
        }
        if (cursor.isBlank() || cursor.length() > MAX_CURSOR_LENGTH) {
            throw invalidCursor();
        }
        try {
            String payload = new String(
                    Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8
            );
            String[] parts = payload.split("\\|", -1);
            if (parts.length != 2) {
                throw invalidCursor();
            }
            Instant createdAt = Instant.parse(parts[0]);
            long productId = Long.parseLong(parts[1]);
            if (productId <= 0) {
                throw invalidCursor();
            }
            return new Cursor(createdAt, productId);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw invalidCursor();
        }
    }

    private static BusinessException invalidCursor() {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "cursor가 올바르지 않습니다.");
    }

    record Cursor(Instant createdAt, Long productId) {
    }
}

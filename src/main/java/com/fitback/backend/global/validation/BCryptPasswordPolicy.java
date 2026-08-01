package com.fitback.backend.global.validation;

import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;

public final class BCryptPasswordPolicy {

    static final int MAX_BYTES = 72;

    private BCryptPasswordPolicy() {
    }

    public static boolean isWithinByteLimit(String password) {
        return password == null
                || password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }

    public static void validate(String password) {
        if (!isWithinByteLimit(password)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }
}

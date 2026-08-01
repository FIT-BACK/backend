package com.fitback.backend.global.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class BCryptPasswordPolicyTest {

    @Test
    void acceptsPasswordUpToSeventyTwoUtf8Bytes() {
        assertThat(BCryptPasswordPolicy.isWithinByteLimit("a".repeat(72))).isTrue();
        assertThat(BCryptPasswordPolicy.isWithinByteLimit("가".repeat(24))).isTrue();
    }

    @Test
    void rejectsPasswordOverSeventyTwoUtf8Bytes() {
        assertThat(BCryptPasswordPolicy.isWithinByteLimit("a".repeat(73))).isFalse();
        assertThat(BCryptPasswordPolicy.isWithinByteLimit("가".repeat(25))).isFalse();

        assertThatThrownBy(() -> BCryptPasswordPolicy.validate("가".repeat(25)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR)
                );
    }
}

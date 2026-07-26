package com.fitback.backend.domain.member.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PasswordResetTokenUtilTest {

    private final PasswordResetTokenUtil passwordResetTokenUtil =
            new PasswordResetTokenUtil();

    @Test
    void generateCreatesUrlSafeTokenAndMatchingHash() {
        PasswordResetTokenUtil.GeneratedToken generatedToken =
                passwordResetTokenUtil.generate();

        assertThat(generatedToken.resetToken())
                .hasSize(43)
                .matches("[A-Za-z0-9_-]+")
                .doesNotContain("=");
        assertThat(generatedToken.tokenHash())
                .isEqualTo(passwordResetTokenUtil.hash(generatedToken.resetToken()))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void generateCreatesUniqueTokens() {
        Set<String> resetTokens = new HashSet<>();

        for (int index = 0; index < 100; index++) {
            resetTokens.add(passwordResetTokenUtil.generate().resetToken());
        }

        assertThat(resetTokens).hasSize(100);
    }

    @Test
    void hashUsesSha256() {
        assertThat(passwordResetTokenUtil.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223"
                        + "b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void hashRejectsNullAndBlankToken() {
        assertThatThrownBy(() -> passwordResetTokenUtil.hash(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> passwordResetTokenUtil.hash(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

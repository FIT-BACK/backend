package com.fitback.backend.domain.image.entity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ImageTest {

    @Test
    void rejectsFileLargerThanFiveMegabytes() {
        Member owner = Member.create(
                "image-entity@fitback.com",
                "image-entity-user",
                "password",
                LoginProvider.EMAIL
        );

        assertThatThrownBy(() -> Image.createPending(
                "35e7f670-aa08-4c3b-b78a-6705f042be31",
                owner,
                "prod/images/profile/2026/07/35e7f670-aa08-4c3b-b78a-6705f042be31.jpg",
                ImagePurpose.PROFILE,
                "image/jpeg",
                5_242_881,
                ImageVisibility.PRIVATE,
                Instant.parse("2026-07-22T05:05:00Z")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fileSize must be between 1 and 5242880");
    }
}

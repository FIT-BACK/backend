package com.fitback.backend.domain.lookbook;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImagePurpose;
import com.fitback.backend.domain.image.entity.ImageVisibility;
import com.fitback.backend.domain.member.entity.Member;
import java.time.Instant;
import java.time.LocalDateTime;

public final class LookbookImageFixtures {

    private LookbookImageFixtures() {
    }

    public static Image readyImage(String imageId, Member owner, ImagePurpose purpose) {
        Image image = Image.createPending(
                imageId,
                owner,
                "lookbook/" + imageId + ".jpg",
                purpose,
                "image/jpeg",
                1024L,
                ImageVisibility.PUBLIC,
                Instant.parse("2026-07-23T00:10:00Z")
        );
        image.completeUpload(
                1024L,
                "image/jpeg",
                LocalDateTime.of(2026, 7, 23, 9, 0)
        );
        return image;
    }
}

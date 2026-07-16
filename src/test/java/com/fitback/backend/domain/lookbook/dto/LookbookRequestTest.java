package com.fitback.backend.domain.lookbook.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class LookbookRequestTest {

    private final Validator validator = Validation
            .buildDefaultValidatorFactory()
            .getValidator();

    @Test
    void lookbookCreateAllowsNullComment() {
        LookbookRequest.LookbookCreate request = createRequest(List.of(10L), null);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void lookbookCreateRejectsNullTagId() {
        LookbookRequest.LookbookCreate request = createRequest(
                Arrays.asList(10L, null),
                "한 줄 코멘트"
        );

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getMessage())
                .contains("태그 ID는 null일 수 없습니다.");
    }

    private LookbookRequest.LookbookCreate createRequest(List<Long> tagIds, String comment) {
        return new LookbookRequest.LookbookCreate(
                "https://s3.example.com/original.jpg",
                "https://s3.example.com/matched.jpg",
                tagIds,
                null,
                comment
        );
    }
}

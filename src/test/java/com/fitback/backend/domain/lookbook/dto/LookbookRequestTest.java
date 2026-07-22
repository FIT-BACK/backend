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
    void lookbookCreateRejectsNullTagIds() {
        LookbookRequest.LookbookCreate request = createRequest(null, "한 줄 코멘트");

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getMessage())
                .contains("스타일 태그는 필수입니다.");
    }

    @Test
    void lookbookCreateRejectsTagCountOutsideOneToFive() {
        LookbookRequest.LookbookCreate emptyTagsRequest = createRequest(List.of(), "한 줄 코멘트");
        LookbookRequest.LookbookCreate tooManyTagsRequest = createRequest(
                List.of(1L, 2L, 3L, 4L, 5L, 6L),
                "한 줄 코멘트"
        );

        assertThat(validator.validate(emptyTagsRequest))
                .extracting(violation -> violation.getMessage())
                .contains("스타일 태그는 1개 이상 5개 이하여야 합니다.");
        assertThat(validator.validate(tooManyTagsRequest))
                .extracting(violation -> violation.getMessage())
                .contains("스타일 태그는 1개 이상 5개 이하여야 합니다.");
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

    @Test
    void lookbookCreateNormalizesBlankPurchaseUrlToNull() {
        LookbookRequest.LookbookCreate request = createRequest(
                List.of(10L),
                "   ",
                "한 줄 코멘트"
        );

        assertThat(request.purchaseUrl()).isNull();
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void lookbookCreateAllowsHttpAndHttpsPurchaseUrls() {
        LookbookRequest.LookbookCreate httpRequest = createRequest(
                List.of(10L),
                "http://shop.example.com/item",
                null
        );
        LookbookRequest.LookbookCreate httpsRequest = createRequest(
                List.of(10L),
                "https://shop.example.com/item",
                null
        );

        assertThat(validator.validate(httpRequest)).isEmpty();
        assertThat(validator.validate(httpsRequest)).isEmpty();
    }

    @Test
    void lookbookCreateRejectsInvalidPurchaseUrl() {
        LookbookRequest.LookbookCreate request = createRequest(
                List.of(10L),
                "ftp://shop.example.com/item",
                null
        );

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getMessage())
                .contains("올바른 링크 형식을 입력해주세요.");
    }

    @Test
    void lookbookUpdateNormalizesBlankPurchaseUrlAndAllowsNullComment() {
        LookbookRequest.LookbookUpdate request = new LookbookRequest.LookbookUpdate(
                "https://s3.example.com/updated-original.jpg",
                "https://s3.example.com/updated-matched.jpg",
                "   ",
                List.of(10L),
                null
        );

        assertThat(request.purchaseUrl()).isNull();
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void lookbookUpdateRejectsTagCountOutsideOneToFive() {
        LookbookRequest.LookbookUpdate request = new LookbookRequest.LookbookUpdate(
                "https://s3.example.com/updated-original.jpg",
                "https://s3.example.com/updated-matched.jpg",
                null,
                List.of(),
                null
        );

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getMessage())
                .contains("스타일 태그는 1개 이상 5개 이하여야 합니다.");
    }

    private LookbookRequest.LookbookCreate createRequest(List<Long> tagIds, String comment) {
        return createRequest(tagIds, null, comment);
    }

    private LookbookRequest.LookbookCreate createRequest(
            List<Long> tagIds,
            String purchaseUrl,
            String comment
    ) {
        return new LookbookRequest.LookbookCreate(
                "https://s3.example.com/original.jpg",
                "https://s3.example.com/matched.jpg",
                purchaseUrl,
                tagIds,
                comment
        );
    }
}

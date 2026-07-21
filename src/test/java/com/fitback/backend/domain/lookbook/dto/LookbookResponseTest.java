package com.fitback.backend.domain.lookbook.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LookbookResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void lookbookListSerializesAccordingToApiSpecification() throws Exception {
        LookbookResponse.LookbookItem item = LookbookResponse.LookbookItem.builder()
                .lookbookId(12L)
                .originalImageUrl("https://original.jpg")
                .matchedImageUrl("https://matched.jpg")
                .authorNickname("mini_style")
                .authorProfileImageUrl("https://profiles/1.jpg")
                .tags(List.of("스트릿"))
                .likeCount(128)
                .isLiked(false)
                .build();
        LookbookResponse.LookbookList response = LookbookResponse.LookbookList.builder()
                .items(List.of(item))
                .nextCursor(12L)
                .hasNext(true)
                .pageSize(5)
                .build();

        String json = objectMapper.writeValueAsString(response);

        assertThat(json)
                .contains("\"authorNickname\":\"mini_style\"")
                .contains("\"authorProfileImageUrl\":\"https://profiles/1.jpg\"")
                .contains("\"tags\":[\"스트릿\"]")
                .contains("\"isLiked\":false")
                .contains("\"pageSize\":5")
                .doesNotContain("\"memberId\"")
                .doesNotContain("\"createdAt\"")
                .doesNotContain("\"likedByMe\"");
    }
}

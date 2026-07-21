package com.fitback.backend.domain.lookbook.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LookbookResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void lookbookCreateSerializesAccordingToApiSpecification() throws Exception {
        LookbookResponse.LookbookCreate response = LookbookResponse.LookbookCreate.builder()
                .lookbookId(12L)
                .build();

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).isEqualTo("{\"lookbookId\":12}");
    }

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

    @Test
    void lookbookDetailSerializesAccordingToApiSpecification() throws Exception {
        LookbookResponse.LookbookDetail response = LookbookResponse.LookbookDetail.builder()
                .originalImageUrl("https://original.jpg")
                .matchedImageUrl("https://matched.jpg")
                .authorNickname("mini_style")
                .authorProfileImageUrl("https://profiles/1.jpg")
                .createdAt(LocalDateTime.of(2026, 7, 21, 18, 30))
                .purchaseUrl("https://shopping.naver.com/item")
                .comment("test")
                .tags(List.of(
                        LookbookResponse.TagItem.builder()
                                .tagId(1L)
                                .tagName("스트릿")
                                .build(),
                        LookbookResponse.TagItem.builder()
                                .tagId(3L)
                                .tagName("와이드핏")
                                .build()
                ))
                .likeCount(128)
                .isLiked(false)
                .isOwner(true)
                .build();

        String json = objectMapper.writeValueAsString(response);

        assertThat(json)
                .contains("\"originalImageUrl\":\"https://original.jpg\"")
                .contains("\"matchedImageUrl\":\"https://matched.jpg\"")
                .contains("\"authorNickname\":\"mini_style\"")
                .contains("\"authorProfileImageUrl\":\"https://profiles/1.jpg\"")
                .contains("\"createdAt\":\"2026-07-21T18:30:00\"")
                .contains("\"purchaseUrl\":\"https://shopping.naver.com/item\"")
                .contains("\"comment\":\"test\"")
                .contains("\"tags\":[{\"tagId\":1,\"tagName\":\"스트릿\"},"
                        + "{\"tagId\":3,\"tagName\":\"와이드핏\"}]")
                .contains("\"likeCount\":128")
                .contains("\"isLiked\":false")
                .contains("\"isOwner\":true")
                .doesNotContain("\"lookbookId\"")
                .doesNotContain("\"memberId\"")
                .doesNotContain("\"likedByMe\"");
    }

    @Test
    void lookbookLikeSerializesAccordingToApiSpecification() throws Exception {
        LookbookResponse.LookbookLike response = LookbookResponse.LookbookLike.builder()
                .isLiked(true)
                .likeCount(129)
                .build();

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).isEqualTo("{\"isLiked\":true,\"likeCount\":129}");
    }

    @Test
    void lookbookUnlikeSerializesAccordingToApiSpecification() throws Exception {
        LookbookResponse.LookbookUnlike response = LookbookResponse.LookbookUnlike.builder()
                .isLiked(false)
                .likeCount(128)
                .build();

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).isEqualTo("{\"isLiked\":false,\"likeCount\":128}");
    }
}

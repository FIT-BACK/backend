package com.fitback.backend.domain.tag.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.tag.dto.TagResponse;
import com.fitback.backend.domain.tag.service.TagService;
import com.fitback.backend.global.response.ApiResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TagControllerTest {

    @Mock
    private TagService tagService;

    @InjectMocks
    private TagController tagController;

    @Test
    void getTagsReturnsSuccessResponse() {
        TagResponse.TagItem item = TagResponse.TagItem.builder()
                .tagId(12L)
                .tagName("와이드핏")
                .build();
        TagResponse.TagList serviceResponse = TagResponse.TagList.builder()
                .items(List.of(item))
                .build();
        when(tagService.getTags()).thenReturn(serviceResponse);

        ApiResponse<TagResponse.TagList> response = tagController.getTags();

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("COMMON200_1");
        assertThat(response.data()).isEqualTo(serviceResponse);
        assertThat(response.data().items()).containsExactly(item);
        verify(tagService).getTags();
    }
}

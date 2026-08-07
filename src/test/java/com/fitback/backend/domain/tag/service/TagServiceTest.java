package com.fitback.backend.domain.tag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.tag.dto.TagResponse;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagTargetClothing;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.domain.tag.repository.TagRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TagServiceTest {

    @Mock
    private TagRepository tagRepository;

    @InjectMocks
    private TagService tagService;

    @Test
    void getTagsReturnsAllTagsOrderedById() {
        Tag minimalTag = Tag.create(
                "와이드핏",
                TagType.SILHOUETTE,
                List.of(TagTargetClothing.PANTS)
        );
        ReflectionTestUtils.setField(minimalTag, "id", 12L);
        Tag colorTag = Tag.create("베이지", TagType.COLOR);
        ReflectionTestUtils.setField(colorTag, "id", 21L);
        when(tagRepository.findAllByOrderByIdAsc()).thenReturn(List.of(minimalTag, colorTag));

        TagResponse.TagList response = tagService.getTags();

        assertThat(response.items())
                .extracting(
                        TagResponse.TagItem::tagId,
                        TagResponse.TagItem::tagName,
                        TagResponse.TagItem::tagType,
                        TagResponse.TagItem::targetClothing
                )
                .containsExactly(
                        tuple(
                                12L,
                                "와이드핏",
                                TagType.SILHOUETTE,
                                List.of(TagTargetClothing.PANTS)
                        ),
                        tuple(21L, "베이지", TagType.COLOR, List.of())
                );
    }

    @Test
    void getTagsReturnsEmptyListWhenNoTagsExist() {
        when(tagRepository.findAllByOrderByIdAsc()).thenReturn(List.of());

        TagResponse.TagList response = tagService.getTags();

        assertThat(response.items()).isEmpty();
    }
}

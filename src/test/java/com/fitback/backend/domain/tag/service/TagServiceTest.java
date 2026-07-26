package com.fitback.backend.domain.tag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitback.backend.domain.tag.dto.TagListResponse;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.domain.tag.repository.TagRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TagServiceTest {

    @Mock
    private TagRepository tagRepository;

    @InjectMocks
    private TagService tagService;

    @Test
    void filtersByTypeAndNormalizedHashQueryWithinLimit() {
        Tag minimal = tag(10L, "미니멀", TagType.DETAIL);
        PageRequest pageRequest = PageRequest.of(0, 8);
        when(tagRepository
                .findByTagTypeAndTagNameContainingIgnoreCaseOrderByTagNameAscIdAsc(
                        TagType.DETAIL,
                        "미니",
                        pageRequest
                ))
                .thenReturn(List.of(minimal));

        TagListResponse response = tagService.getTags(TagType.DETAIL, "  #미니  ", 8);

        assertThat(response.count()).isEqualTo(1);
        assertThat(response.items())
                .extracting("tagId", "tagName", "tagType")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        10L,
                        "미니멀",
                        TagType.DETAIL
                ));
    }

    @Test
    void treatsBlankQueryAsNoQuery() {
        PageRequest pageRequest = PageRequest.of(0, 50);
        when(tagRepository.findAllByOrderByTagNameAscIdAsc(pageRequest))
                .thenReturn(List.of());

        TagListResponse response = tagService.getTags(null, " # ", 50);

        assertThat(response.items()).isEmpty();
        verify(tagRepository).findAllByOrderByTagNameAscIdAsc(pageRequest);
    }

    @Test
    void filtersByTypeOnly() {
        PageRequest pageRequest = PageRequest.of(0, 20);
        when(tagRepository.findByTagTypeOrderByTagNameAscIdAsc(
                TagType.COLOR,
                pageRequest
        )).thenReturn(List.of());

        tagService.getTags(TagType.COLOR, null, 20);

        verify(tagRepository).findByTagTypeOrderByTagNameAscIdAsc(
                TagType.COLOR,
                pageRequest
        );
    }

    @Test
    void filtersByQueryOnly() {
        PageRequest pageRequest = PageRequest.of(0, 10);
        when(tagRepository.findByTagNameContainingIgnoreCaseOrderByTagNameAscIdAsc(
                "와이드",
                pageRequest
        )).thenReturn(List.of());

        tagService.getTags(null, "와이드", 10);

        verify(tagRepository).findByTagNameContainingIgnoreCaseOrderByTagNameAscIdAsc(
                "와이드",
                pageRequest
        );
    }

    private Tag tag(Long id, String name, TagType type) {
        Tag tag = Tag.create(name, type);
        ReflectionTestUtils.setField(tag, "id", id);
        return tag;
    }
}

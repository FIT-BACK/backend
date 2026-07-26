package com.fitback.backend.domain.tag.dto;

import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import java.util.List;

public record TagListResponse(
        List<Item> items,
        int count
) {

    public TagListResponse {
        items = List.copyOf(items);
    }

    public static TagListResponse from(List<Tag> tags) {
        List<Item> items = tags.stream()
                .map(Item::from)
                .toList();
        return new TagListResponse(items, items.size());
    }

    public record Item(
            Long tagId,
            String tagName,
            TagType tagType
    ) {

        private static Item from(Tag tag) {
            return new Item(tag.getId(), tag.getTagName(), tag.getTagType());
        }
    }
}

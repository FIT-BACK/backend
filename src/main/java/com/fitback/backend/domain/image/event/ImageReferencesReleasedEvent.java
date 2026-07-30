package com.fitback.backend.domain.image.event;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

public record ImageReferencesReleasedEvent(List<String> imageIds) {

    public ImageReferencesReleasedEvent(Collection<String> imageIds) {
        this(imageIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList());
    }

    public ImageReferencesReleasedEvent {
        imageIds = List.copyOf(imageIds);
    }
}

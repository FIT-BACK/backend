package com.fitback.backend.domain.product.service.model;

import java.net.URI;
import java.util.List;

public record ReferenceProductDiscoveryQuery(URI imageUri, List<String> confirmedTags) {

    public ReferenceProductDiscoveryQuery {
        imageUri = ModelValidation.requireHttpUri(imageUri, "imageUri");
        confirmedTags = confirmedTags == null ? List.of() : confirmedTags.stream()
                .map(tag -> ModelValidation.requireNonBlank(tag, "confirmedTag"))
                .distinct()
                .toList();
    }
}

package com.fitback.backend.domain.image.service;

import com.fitback.backend.domain.image.entity.ImageAsset;

public interface ImageAccessUrlProvider {

    String createReadUrl(ImageAsset imageAsset);
}

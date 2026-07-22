package com.fitback.backend.domain.image.service;

import com.fitback.backend.domain.image.entity.Image;

public interface ImageAccessUrlProvider {

    String createReadUrl(Image image);
}

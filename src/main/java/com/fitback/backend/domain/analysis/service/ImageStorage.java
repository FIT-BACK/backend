package com.fitback.backend.domain.analysis.service;

import org.springframework.web.multipart.MultipartFile;

public interface ImageStorage {

    String store(MultipartFile image);

    void delete(String imageUrl);
}

package com.fitback.backend.domain.image.repository;

import com.fitback.backend.domain.image.entity.Image;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageRepository extends JpaRepository<Image, String> {
}

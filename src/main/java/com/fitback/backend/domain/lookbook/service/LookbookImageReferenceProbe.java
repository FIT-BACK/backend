package com.fitback.backend.domain.lookbook.service;

import com.fitback.backend.domain.image.service.ImageReferenceProbe;
import com.fitback.backend.domain.lookbook.repository.LookbookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LookbookImageReferenceProbe implements ImageReferenceProbe {

    private final LookbookRepository lookbookRepository;

    @Override
    public boolean exists(String imageId) {
        return lookbookRepository.existsActiveImageReference(imageId);
    }
}

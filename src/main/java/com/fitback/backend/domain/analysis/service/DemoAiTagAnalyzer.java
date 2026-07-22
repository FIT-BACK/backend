package com.fitback.backend.domain.analysis.service;

import com.fitback.backend.domain.image.entity.ImageAsset;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.repository.TagRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
@RequiredArgsConstructor
public class DemoAiTagAnalyzer implements AiTagAnalyzer {

    private final TagRepository tagRepository;

    @Override
    public List<Tag> analyze(MultipartFile image) {
        return tagRepository.findTop3ByOrderByIdAsc();
    }

    @Override
    public List<Tag> analyze(ImageAsset image) {
        return tagRepository.findTop3ByOrderByIdAsc();
    }
}

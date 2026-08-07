package com.fitback.backend.domain.analysis.service;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.repository.TagRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
@Profile("prod")
@ConditionalOnProperty(name = "fitback.ai.tag-analyzer", havingValue = "prototype")
@RequiredArgsConstructor
public class PrototypeAiTagAnalyzer implements AiTagAnalyzer {

    private static final List<String> PROTOTYPE_TAG_NAMES =
            List.of("미니멀", "와이드핏", "베이지");

    private final TagRepository tagRepository;

    @Override
    public List<Tag> analyze(MultipartFile image) {
        throw new BusinessException(ErrorCode.ANALYSIS_IMAGE_UPLOAD_FLOW_REQUIRED);
    }

    @Override
    public List<Tag> analyze(Image image) {
        Map<String, Tag> tagsByName = tagRepository.findAllByTagNameIn(PROTOTYPE_TAG_NAMES).stream()
                .collect(Collectors.toMap(Tag::getTagName, Function.identity()));
        if (tagsByName.size() != PROTOTYPE_TAG_NAMES.size()) {
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_READY);
        }
        return PROTOTYPE_TAG_NAMES.stream()
                .map(tagsByName::get)
                .toList();
    }
}

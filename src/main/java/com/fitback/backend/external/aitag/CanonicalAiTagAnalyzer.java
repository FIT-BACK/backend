package com.fitback.backend.external.aitag;

import com.fitback.backend.domain.analysis.service.AiTagAnalyzer;
import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.tag.entity.Tag;
import com.fitback.backend.domain.tag.entity.TagType;
import com.fitback.backend.domain.tag.repository.TagRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.multipart.MultipartFile;

public final class CanonicalAiTagAnalyzer implements AiTagAnalyzer {

    private final TagRepository tagRepository;
    private final AnalysisImageContentLoader imageContentLoader;
    private final AiTagRequestFactory requestFactory;
    private final AiTagModelClient modelClient;

    public CanonicalAiTagAnalyzer(
            TagRepository tagRepository,
            AnalysisImageContentLoader imageContentLoader,
            AiTagRequestFactory requestFactory,
            AiTagModelClient modelClient
    ) {
        this.tagRepository = tagRepository;
        this.imageContentLoader = imageContentLoader;
        this.requestFactory = requestFactory;
        this.modelClient = modelClient;
    }

    @Override
    public List<Tag> analyze(MultipartFile image) {
        try {
            return analyze(new AiTagImage(image.getBytes(), image.getContentType()));
        } catch (IOException | IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_ANALYSIS_IMAGE);
        }
    }

    @Override
    public List<Tag> analyze(Image image) {
        return analyze(imageContentLoader.load(image));
    }

    private List<Tag> analyze(AiTagImage image) {
        List<Tag> catalog = tagRepository.findAllByOrderByIdAsc();
        if (catalog.isEmpty()) {
            throw notReady();
        }
        Map<TagKey, Tag> canonicalTags = catalog.stream().collect(Collectors.toMap(
                tag -> new TagKey(tag.getTagType(), tag.getTagName()),
                Function.identity()
        ));
        AiTagModelResult result = modelClient.analyze(image, requestFactory.create(catalog));
        List<TagKey> predictedKeys = result.garments().stream()
                .flatMap(garment -> garment.canonicalTags().stream())
                .map(prediction -> new TagKey(prediction.type(), prediction.name()))
                .distinct()
                .toList();
        if (predictedKeys.isEmpty()
                || predictedKeys.size()
                > AiTagRequestFactory.MAX_GARMENTS * AiTagRequestFactory.MAX_TAGS_PER_GARMENT
                || predictedKeys.stream().anyMatch(key -> !canonicalTags.containsKey(key))) {
            throw notReady();
        }
        return predictedKeys.stream().map(canonicalTags::get).toList();
    }

    private BusinessException notReady() {
        return new BusinessException(ErrorCode.ANALYSIS_NOT_READY);
    }

    private record TagKey(TagType type, String name) {
    }
}

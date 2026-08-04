package com.fitback.backend.external.aitag;

import com.fitback.backend.domain.tag.entity.TagType;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class AiTagResponseParser {

    private final ObjectMapper objectMapper;

    public AiTagResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AiTagModelOutput parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode canonicalTags = root.path("canonicalTags");
            JsonNode suggestedTags = root.path("suggestedTags");
            if (!canonicalTags.isArray()) {
                throw new IllegalArgumentException("canonicalTags must be an array");
            }
            if (!suggestedTags.isArray()) {
                throw new IllegalArgumentException("suggestedTags must be an array");
            }
            List<AiTagPrediction> predictions = new ArrayList<>();
            for (JsonNode tag : canonicalTags) {
                predictions.add(new AiTagPrediction(
                        TagType.valueOf(tag.path("type").asText()),
                        tag.path("name").asText()
                ));
            }
            List<AiTagSuggestion> suggestions = new ArrayList<>();
            for (JsonNode tag : suggestedTags) {
                suggestions.add(new AiTagSuggestion(
                        TagType.valueOf(tag.path("type").asText()),
                        tag.path("name").asText(),
                        tag.path("confidence").asDouble(),
                        tag.path("evidence").asText()
                ));
            }
            return new AiTagModelOutput(predictions, suggestions);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid AI tag response", exception);
        }
    }
}
